package veeci.practicing.rts.fleet.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import veeci.practicing.rts.fleet.application.port.out.FleetPresenceRepository
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.jobs.BackgroundWorker
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.DriverHeard
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * The fleet's server-clock presence (fleet lag fix C): when the server last heard from each driver, written to
 * `fleet_drivers.heard_at`, which "online" reads.
 *
 * Ingest calls [heard] on every hello, batch and device_status, inside its own transaction. [heard] only records the
 * newest time per driver in memory, so the hot path gains no database write. Every [flushEvery] the worker writes what
 * it collected in one transaction: each driver at most once per flush, however many batches it sent. Nothing here waits
 * for the outbox relay, so a relay backlog no longer delays "online".
 *
 * Each instance flushes the drivers it serves; newer wins in SQL, so two instances never move `heard_at` back. A crash
 * loses at most one flush, which the driver's next batch or heartbeat (every 30 s) replaces.
 */
class FleetPresence(
    private val presence: FleetPresenceRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val flushEvery: Duration,
) : DriverHeard,
    BackgroundWorker {
    private val log = LoggerFactory.getLogger(FleetPresence::class.java)
    private val waiting = ConcurrentHashMap<UUID, Instant>()

    /** The loop and the final flush at [stop] never write at once. */
    private val flushing = Mutex()

    @Volatile
    private var loop: Job? = null

    /** How many drivers wait for the next flush. */
    internal val pending: Int get() = waiting.size

    override fun heard(
        driver: UserId,
        at: Instant,
    ) {
        waiting.merge(driver.value, at, ::maxOf)
    }

    override fun start(scope: CoroutineScope) {
        loop =
            scope.launch(CoroutineName("fleet-presence")) {
                while (isActive) {
                    delay(flushEvery)
                    flushSafely()
                }
            }
    }

    /** Stops the loop, then writes what is left, so a graceful shutdown keeps the last few seconds. */
    override suspend fun stop() {
        loop?.cancelAndJoin()
        flushSafely()
    }

    /**
     * Writes every collected time. A driver whose row the relay holds stays in memory for the next flush, and a driver
     * with no row gets one, each in a transaction of its own, which may wait for the relay but holds no other row. On a
     * failure everything not written goes back, newer wins against what arrived meanwhile, and the next flush tries again.
     * The loop calls it; a test may call it to skip the wait.
     */
    suspend fun flush() {
        flushing.withLock {
            val batch = drain()
            if (batch.isEmpty()) return
            val updatedAt = clock.instant().toKotlinInstant()
            // Written, or put back as busy. Whatever else is in the batch when the block ends goes back.
            var handled = emptySet<UUID>()
            try {
                val left = transactions.inTransaction { presence.saveHeard(batch, updatedAt) }
                left.busy.forEach(::requeue)
                handled = batch.keys - left.missing.keys
                left.missing.forEach { (driver, at) ->
                    transactions.inTransaction { presence.create(driver, at, updatedAt) }
                    handled = handled + driver
                }
            } finally {
                // A failure, or a cancel at shutdown, keeps what was not written for the next flush.
                (batch - handled).forEach(::requeue)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // a failed flush (database down) is logged; the times wait for the next one
    private suspend fun flushSafely() {
        try {
            flush()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            log.warn("Fleet presence flush failed; it runs again in {}", flushEvery, e)
        } catch (e: Exception) {
            log.warn("Fleet presence flush failed; it runs again in {}", flushEvery, e)
        }
    }

    /** Takes every waiting time out of the map, one key at a time, so a time that arrives meanwhile is never lost. */
    private fun drain(): Map<UUID, Instant> {
        val batch = HashMap<UUID, Instant>()
        waiting.keys.forEach { id -> waiting.remove(id)?.let { batch[id] = it } }
        return batch
    }

    private fun requeue(
        driver: UUID,
        at: Instant,
    ) {
        waiting.merge(driver, at, ::maxOf)
    }
}
