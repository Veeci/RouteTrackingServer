package veeci.practicing.rts.platform.events

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
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
 * Delivers the outbox to the handlers, oldest event first. Each event is one transaction: lock the oldest
 * undelivered row (`FOR UPDATE SKIP LOCKED`), run every handler of its type, then set `published_at`. With several
 * instances, only one delivers at a time (an advisory lock, phase 10): with SKIP LOCKED alone, two instances would
 * handle two events of one trip at the same time, out of order. If a handler fails, the transaction rolls back and the
 * event is tried again later; after [MAX_ATTEMPTS] failures it is logged and skipped, so that one broken event
 * cannot stop the others. A crash between handling and commit delivers the event again: at least once.
 */
class OutboxRelay(
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
     * Stops delivering and waits until the event in hand is done (its transaction commits or rolls back).
     * Events not delivered yet stay in the outbox for the next start: at least once.
     */
    suspend fun stop() {
        job?.cancelAndJoin()
    }

    /** Delivers up to one batch of waiting events and returns how many it delivered. */
    suspend fun deliverPending(): Int {
        var delivered = 0
        while (delivered < config.relayBatchSize && deliverNext()) delivered++
        return delivered
    }

    /** False when nothing is waiting, or when the next event failed (it is retried on the next round). */
    @Suppress("TooGenericExceptionCaught") // whatever a handler throws, the relay must keep running
    private suspend fun deliverNext(): Boolean {
        var eventId: Long? = null
        return try {
            transactions
                .inTransaction {
                    // One instance delivers at a time, so the events of one trip are handled in order (phase 10).
                    if (!holdsDeliveryLock()) return@inTransaction false
                    val row =
                        OutboxTable
                            .selectAll()
                            .where { OutboxTable.publishedAt.isNull() }
                            .orderBy(OutboxTable.id to SortOrder.ASC)
                            .limit(1)
                            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(mode = ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                            .singleOrNull() ?: return@inTransaction false
                    eventId = row[OutboxTable.id]
                    handlers[row[OutboxTable.eventType]].orEmpty().forEach { it.handle(row[OutboxTable.payload]) }
                    markPublished(row[OutboxTable.id])
                    true
                }.also {
                    if (it) {
                        eventId?.let(failures::remove)
                        delivered?.increment()
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            eventId?.let { giveUpOrRetry(it, e) } ?: log.error("Reading the outbox failed", e)
            false
        }
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
            transactions.inTransaction { markPublished(id) }
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

    private fun markPublished(id: Long) {
        OutboxTable.update({ OutboxTable.id eq id }) { it[publishedAt] = CurrentTimestamp }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5

        /** Unique among advisory lock keys: jobs use 4_001 and up by phase; 10 for phase 10, 1 for the relay. */
        const val DELIVERY_LOCK_KEY = 10_001L
    }
}
