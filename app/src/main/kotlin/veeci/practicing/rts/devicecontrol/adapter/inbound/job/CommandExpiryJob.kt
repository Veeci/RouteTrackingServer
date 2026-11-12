package veeci.practicing.rts.devicecontrol.adapter.inbound.job

import veeci.practicing.rts.devicecontrol.application.CommandService
import veeci.practicing.rts.platform.jobs.ScheduledJob
import kotlin.time.Duration

/** Every [period], expires the commands that waited too long for their ack. They are never sent after that. */
class CommandExpiryJob(
    private val commands: CommandService,
    override val period: Duration,
) : ScheduledJob {
    override val name = "command-expiry"
    override val lockKey = LOCK_KEY

    override suspend fun run() {
        commands.expireDue()
    }

    private companion object {
        /** Unique among jobs: 5 for phase 5, 1 for its first job. */
        const val LOCK_KEY = 5_001L
    }
}
