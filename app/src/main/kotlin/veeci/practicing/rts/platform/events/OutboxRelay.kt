package veeci.practicing.rts.platform.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * undelivered row (`FOR UPDATE SKIP LOCKED`, so a second server instance takes the next row instead of waiting),
 * run every handler of its type, then set `published_at`. If a handler fails, the transaction rolls back and the
 * event is tried again later; after [MAX_ATTEMPTS] failures it is logged and skipped, so that one broken event
 * cannot stop the others. A crash between handling and commit delivers the event again: at least once.
 */
class OutboxRelay(
    private val transactions: TransactionRunner,
    handlers: List<OutboxEventHandler>,
    private val config: EventsConfig,
) {
    private val log = LoggerFactory.getLogger(OutboxRelay::class.java)
    private val handlers = handlers.groupBy { it.eventType }
    private val failures = ConcurrentHashMap<Long, Int>()

    /** Runs until [scope] ends (the application stops). */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                val delivered = deliverPending()
                // A full batch means more may be waiting: go on at once. Otherwise wait before looking again.
                if (delivered < config.relayBatchSize) delay(config.relayInterval)
            }
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
                }.also { if (it) eventId?.let(failures::remove) }
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

    private fun markPublished(id: Long) {
        OutboxTable.update({ OutboxTable.id eq id }) { it[publishedAt] = CurrentTimestamp }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
