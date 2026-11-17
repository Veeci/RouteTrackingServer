package veeci.practicing.rts.devicecontrol.application.api

import veeci.practicing.rts.shared.UserId
import kotlin.time.Instant

/**
 * Device control's public API for session health (phase 9): which fix interval a driver's device was told to keep,
 * and since when. Joins the caller's transaction when there is one.
 */
interface AppliedConstraints {
    /**
     * The SET_CONSTRAINT commands the [driver]'s device applied in [from, to), plus the last one applied before
     * [from], which was still in effect at [from]. Oldest first. A command counts from its ack, not from its issue.
     */
    suspend fun history(
        driver: UserId,
        from: Instant,
        to: Instant,
    ): List<AppliedConstraint>
}

data class AppliedConstraint(
    val appliedAt: Instant,
    /** 0 means "as fast as the device can". */
    val minIntervalMs: Long,
)
