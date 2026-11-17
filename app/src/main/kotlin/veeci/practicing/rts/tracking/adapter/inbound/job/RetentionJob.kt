package veeci.practicing.rts.tracking.adapter.inbound.job

import io.micrometer.core.instrument.MeterRegistry
import veeci.practicing.rts.platform.jobs.ScheduledJob
import veeci.practicing.rts.platform.security.WsTickets
import veeci.practicing.rts.tracking.application.LocationRetention
import kotlin.time.Duration

/**
 * Every [period], deletes the next sessions' raw location data that is past retention (phase 10), and the fleet socket
 * tickets that expired unused (phase 11c).
 */
class RetentionJob(
    private val retention: LocationRetention,
    private val tickets: WsTickets,
    metrics: MeterRegistry,
    override val period: Duration,
) : ScheduledJob {
    override val name = "location-retention"
    override val lockKey = LOCK_KEY

    /** rts_retention_deleted_total{table}: proves the job runs, and shows how much it deletes. */
    private val deletedFixes = metrics.counter("rts.retention.deleted", "table", "fixes")
    private val deletedSamples = metrics.counter("rts.retention.deleted", "table", "device_status_samples")
    private val deletedTickets = metrics.counter("rts.retention.deleted", "table", "ws_tickets")

    override suspend fun run() {
        val purged = retention.purgeExpired()
        deletedFixes.increment(purged.fixes.toDouble())
        deletedSamples.increment(purged.statusSamples.toDouble())
        deletedTickets.increment(tickets.purgeExpired().toDouble())
    }

    private companion object {
        /** Unique among jobs: 10 for phase 10, 2 for the second lock of the phase (the relay has 10_001). */
        const val LOCK_KEY = 10_002L
    }
}
