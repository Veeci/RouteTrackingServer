package veeci.practicing.rts.platform.events

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.config.EventsConfig
import veeci.practicing.rts.platform.db.TransactionRunner
import java.util.concurrent.ConcurrentHashMap

/** How handlers read event payloads: a field added by a newer writer is ignored, not an error. */
val EventJson: Json = Json { ignoreUnknownKeys = true }

/** Reacts to one type of event from the outbox. Contexts bind their handlers in Koin; the relay finds all of them. */
interface OutboxEventHandler {
    /** For example `tracking.FixesAccepted.v1`. */
    val eventType: String

    /** Must be safe to run twice for the same event: delivery is at least once. */
    suspend fun handle(payload: JsonElement)
}

/**
 * Delivers the outbox to the handlers, oldest event first. One pass is one transaction (load check 11): lock up to
 * [EventsConfig.relayBatchSize] of the oldest undelivered rows (`FOR UPDATE SKIP LOCKED`), run every handler of each
 * event in order, set `published_at` on all of them with one UPDATE, and commit once. With several instances, only
 * one delivers at a time (an advisory lock, phase 10): with SKIP LOCKED alone, two instances would handle two events
 * of one trip at the same time, out of order.
 *
 * If a handler fails, the whole pass rolls back. The failure counts against that event, and the events before it
 * are delivered again without it, so a failing event never holds back the events that came before it. After
 * [MAX_ATTEMPTS] failures the event is logged and skipped, so that it cannot stop the others. A crash between
 * handling and commit delivers the events again: at least once.
 */
class OutboxRelay(
    /** On the relay's own pool (`db.relayPoolSize`); the handlers' own runner joins its open transaction. */
    private val transactions: TransactionRunner,
    handlers: List<OutboxEventHandler>,
    private val config: EventsConfig,
    metrics: MeterRegistry? = null,
) {
    /** rts_relay_delivered_total: events per second, the relay's ceiling before the capacity test (phase 10). */
    private val delivered = metrics?.counter("rts.relay.delivered")

    private val log = LoggerFactory.getLogger(OutboxRelay::class.java)
    private val handlers = handlers.groupBy { it.eventType }
    private val failures = ConcurrentHashMap<Long, Int>()

    @Volatile
    private var job: Job? = null

    /** True between [start] and the end of the delivery loop. */
    val isRunning: Boolean get() = job?.isActive == true

    /** Runs until [scope] ends (the application stops). */
    fun start(scope: CoroutineScope): Job =
        scope
            .launch {
                while (isActive) {
                    val delivered = deliverPending()
                    // A full batch means more may be waiting: go on at once. Otherwise wait before looking again.
                    if (delivered < config.relayBatchSize) delay(config.relayInterval)
                }
            }.also { job = it }

    /**
     * Stops delivering and waits until the batch in hand is done (its transaction commits or rolls back).
     * Events not delivered yet stay in the outbox for the next start: at least once.
     */
    suspend fun stop() {
        job?.cancelAndJoin()
    }

    /** Delivers up to one batch of waiting events and returns how many it delivered. */
    suspend fun deliverPending(): Int {
        val failed =
            try {
                return deliverBatch(before = null)
            } catch (e: BatchFailed) {
                e.record()
            }
        // The events before the failed one did not fail: deliver them again, this time without it.
        return failed?.takeIf { it.handledBefore > 0 }?.eventId?.let { deliverBefore(it) } ?: 0
    }

    private suspend fun deliverBefore(failedId: Long): Int =
        try {
            deliverBatch(before = failedId)
        } catch (e: BatchFailed) {
            e.record()
            0
        }

    /** One transaction: the oldest undelivered events (only those with an id below [before], when it is set). */
    @Suppress("TooGenericExceptionCaught") // whatever a handler throws, the relay must keep running
    private suspend fun deliverBatch(before: Long?): Int {
        var current: Long? = null
        var handled = 0
        val ids =
            try {
                transactions.inTransaction {
                    // One instance delivers at a time, so the events of one trip are handled in order (phase 10).
                    if (!holdsDeliveryLock()) return@inTransaction emptyList()
                    val rows = oldestUndelivered(before)
                    for (row in rows) {
                        current = row[OutboxTable.id]
                        handlers[row[OutboxTable.eventType]].orEmpty().forEach { it.handle(row[OutboxTable.payload]) }
                        handled++
                    }
                    current = null
                    rows.map { it[OutboxTable.id] }.also { if (it.isNotEmpty()) markPublished(it) }
                }
            } catch (e: CancellationException) {
                // Rethrow only when the relay itself is being stopped. A handler can throw CancellationException
                // while the relay is active (a driver socket that closes during send). Rethrown, it would end the
                // delivery loop without a log line, and no event would be delivered again until a restart.
                currentCoroutineContext().ensureActive()
                throw BatchFailed(current, handled, e)
            } catch (e: Exception) {
                throw BatchFailed(current, handled, e)
            }
        ids.forEach(failures::remove)
        delivered?.increment(ids.size.toDouble())
        return ids.size
    }

    private fun oldestUndelivered(before: Long?) =
        OutboxTable
            .selectAll()
            .where {
                val undelivered = OutboxTable.publishedAt.isNull()
                if (before == null) undelivered else undelivered and (OutboxTable.id less before)
            }.orderBy(OutboxTable.id to SortOrder.ASC)
            .limit(config.relayBatchSize)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(mode = ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .toList()

    /** A pass rolled back. [eventId] is the event whose handler failed; null when the failure was elsewhere. */
    private class BatchFailed(
        val eventId: Long?,
        /** How many events of the pass were handled before the failure. */
        val handledBefore: Int,
        val error: Exception,
    ) : Exception(error)

    /** Counts the failure against its event, or logs it when no event was at fault. Returns the failure, or null. */
    private suspend fun BatchFailed.record(): BatchFailed? {
        if (eventId == null) {
            log.error("Delivering an outbox batch failed", error)
            return null
        }
        giveUpOrRetry(eventId, error)
        return this
    }

    private suspend fun giveUpOrRetry(
        id: Long,
        cause: Exception,
    ) {
        val attempts = failures.merge(id, 1, Int::plus) ?: 1
        if (attempts < MAX_ATTEMPTS) {
            log.warn("Delivering outbox event {} failed (attempt {} of {}); it is retried", id, attempts, MAX_ATTEMPTS, cause)
        } else {
            log.error("Delivering outbox event {} failed {} times; it is skipped", id, attempts, cause)
            transactions.inTransaction { markPublished(listOf(id)) }
            failures.remove(id)
        }
    }

    /**
     * `pg_try_advisory_xact_lock`: true when this transaction may deliver. Postgres frees the lock at commit or
     * rollback, so a crashed instance never keeps it, and the next event may go to any instance. Order still holds:
     * whoever holds the lock takes the oldest undelivered event.
     */
    private fun holdsDeliveryLock(): Boolean =
        TransactionManager.current().exec("SELECT pg_try_advisory_xact_lock($DELIVERY_LOCK_KEY)") { rows ->
            rows.next() && rows.getBoolean(1)
        } ?: false

    private fun markPublished(ids: List<Long>) {
        OutboxTable.update({ OutboxTable.id inList ids }) { it[publishedAt] = CurrentTimestamp }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5

        /** Unique among advisory lock keys: jobs use 4_001 and up by phase; 10 for phase 10, 1 for the relay. */
        const val DELIVERY_LOCK_KEY = 10_001L
    }
}
