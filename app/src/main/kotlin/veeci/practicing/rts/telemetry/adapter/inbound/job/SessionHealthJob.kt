package veeci.practicing.rts.telemetry.adapter.inbound.job

import veeci.practicing.rts.platform.jobs.ScheduledJob
import veeci.practicing.rts.telemetry.application.SessionHealthService
import kotlin.time.Duration

/** Every [period], computes the health of sessions that went idle since the last run. */
class SessionHealthJob(
    private val service: SessionHealthService,
    override val period: Duration,
) : ScheduledJob {
    override val name = "session-health"
    override val lockKey = LOCK_KEY

    override suspend fun run() {
        service.computeIdle()
    }

    private companion object {
        /** Any number unique among jobs: 9 for phase 9, 1 for the first job. */
        const val LOCK_KEY = 9_001L
    }
}
