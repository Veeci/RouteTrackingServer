package veeci.practicing.rts.platform.events

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.jobs.BackgroundWorker
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The outbox backlog as gauges (phase 10): `rts_outbox_pending` and `rts_outbox_oldest_age_seconds`. Since 10a one
 * instance delivers at a time, so a relay that falls behind shows here first: HTTP and ack latency stay fine
 * while guests see positions late. Every instance reads them every [every] with one query on the partial index
 * `outbox_unpublished`, rather than one query per scrape.
 */
class OutboxMetrics(
    private val transactions: TransactionRunner,
    metrics: MeterRegistry,
    private val every: Duration = 15.seconds,
) : BackgroundWorker {
    private val log = LoggerFactory.getLogger(OutboxMetrics::class.java)
    private val pending = AtomicLong()
    private val oldestAgeS = AtomicLong()
    private var job: Job? = null

    init {
        metrics.gauge("rts.outbox.pending", pending) { it.get().toDouble() }
        metrics.gauge("rts.outbox.oldest.age.seconds", oldestAgeS) { it.get().toDouble() }
    }

    override fun start(scope: CoroutineScope) {
        job =
            scope.launch {
                while (isActive) {
                    refresh()
                    delay(every)
                }
            }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
    }

    /** Reads the backlog once. A failure keeps the last values and is logged: a metric must never stop the app. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun refresh() {
        try {
            val (count, age) =
                transactions.inTransaction {
                    TransactionManager.current().exec(BACKLOG_SQL) { rows ->
                        rows.next()
                        rows.getLong(1) to rows.getLong(2)
                    } ?: (0L to 0L)
                }
            pending.set(count)
            oldestAgeS.set(age)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Reading the outbox backlog failed: {}", e.toString())
        }
    }

    private companion object {
        const val BACKLOG_SQL =
            "SELECT count(*), coalesce(extract(epoch FROM now() - min(created_at)), 0)::bigint " +
                "FROM outbox WHERE published_at IS NULL"
    }
}
