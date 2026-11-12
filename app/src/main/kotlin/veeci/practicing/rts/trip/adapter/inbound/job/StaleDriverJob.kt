package veeci.practicing.rts.trip.adapter.inbound.job

import veeci.practicing.rts.platform.jobs.ScheduledJob
import veeci.practicing.rts.trip.application.TripEventService
import kotlin.time.Duration

/** Every [period], checks whether the drivers of active trips went silent or came back. */
class StaleDriverJob(
    private val events: TripEventService,
    override val period: Duration,
) : ScheduledJob {
    override val name = "stale-drivers"
    override val lockKey = LOCK_KEY

    override suspend fun run() = events.detectStale()

    private companion object {
        /** Any number unique among jobs: 4 for phase 4, 1 for the first job. */
        const val LOCK_KEY = 4_001L
    }
}
