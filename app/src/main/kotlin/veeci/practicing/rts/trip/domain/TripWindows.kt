package veeci.practicing.rts.trip.domain

import veeci.practicing.rts.trip.domain.event.TripStatus
import kotlin.time.Instant

/** The times [from, to) in which the driver's fixes belong to one leg of a trip. */
data class TimeWindow(
    val from: Instant,
    val to: Instant,
)

/** The two parts of a trip the driver drives: to the pickup (APPROACH), then with the guest (RIDE). */
enum class TripLeg { RIDE, APPROACH }

/**
 * Whether the trip has both ride times. A trip stored before V13 has none, and its ride window falls back to
 * created_at..updated_at when it is COMPLETED (see [rideWindow]).
 */
val Trip.hasExactRide: Boolean
    get() = stages.startedAt != null && stages.endedAt != null

/**
 * When the guest was in the car: started_at..ended_at. A COMPLETED trip stored before V13 has neither time, and
 * its ride is created_at..updated_at, which holds the approach too. Null for a trip whose ride never started or
 * has not ended. The summary and the track both use this, so they never disagree about which fixes are the ride.
 */
fun Trip.rideWindow(): TimeWindow? =
    when {
        hasExactRide -> TimeWindow(checkNotNull(stages.startedAt), checkNotNull(stages.endedAt))
        status == TripStatus.COMPLETED && stages == StageTimes.NONE -> TimeWindow(createdAt, updatedAt)
        else -> null
    }

/** When the driver drove to the pickup: accepted_at..arrived_at, or null when the trip lacks either time. */
fun Trip.approachWindow(): TimeWindow? =
    stages.acceptedAt?.let { accepted -> stages.arrivedAt?.let { arrived -> TimeWindow(accepted, arrived) } }

/** The window of [leg]; null for an approach the trip never had. */
fun Trip.window(leg: TripLeg): TimeWindow? =
    when (leg) {
        TripLeg.RIDE -> rideWindow()
        TripLeg.APPROACH -> approachWindow()
    }
