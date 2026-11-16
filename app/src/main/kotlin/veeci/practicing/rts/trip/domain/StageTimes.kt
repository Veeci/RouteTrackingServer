package veeci.practicing.rts.trip.domain

import veeci.practicing.rts.trip.domain.event.TripStatus
import kotlin.time.Instant

/**
 * When a trip reached each stage after REQUESTED (migration V13). A stage the trip never reached is null, and so
 * is every stage of a trip stored before V13. [endedAt] is set by COMPLETED and by CANCELLED.
 */
data class StageTimes(
    val acceptedAt: Instant? = null,
    val arrivedAt: Instant? = null,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
) {
    /** These times after the trip reached [stage] at [at]. The state machine never repeats a stage. */
    fun reached(
        stage: TripStatus,
        at: Instant,
    ): StageTimes =
        when (stage) {
            TripStatus.REQUESTED -> this
            TripStatus.ACCEPTED -> copy(acceptedAt = at)
            TripStatus.ARRIVED -> copy(arrivedAt = at)
            TripStatus.IN_PROGRESS -> copy(startedAt = at)
            TripStatus.COMPLETED, TripStatus.CANCELLED -> copy(endedAt = at)
        }

    companion object {
        val NONE = StageTimes()
    }
}
