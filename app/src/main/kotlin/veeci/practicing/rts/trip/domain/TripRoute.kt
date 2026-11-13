package veeci.practicing.rts.trip.domain

import kotlin.time.Instant

/** Which leg of a trip a route covers. */
enum class RouteKind {
    /** Pickup to destination, planned when the trip is booked. */
    TRIP,

    /** The driver's position to the pickup. Not planned yet: phase 7 adds it once tracking can say where a driver is. */
    APPROACH,
}

/**
 * A route the trip context keeps for a trip. It stores the encoded polyline and the totals, not the routing
 * context's types: the trip owns this copy, and a later change of the planner does not change a booked trip.
 */
data class TripRoute(
    val tripId: TripId,
    val kind: RouteKind,
    /** 1 for the first plan of this kind; a re-plan stores the next number. */
    val version: Int,
    /** Google Encoded Polyline, precision 5. */
    val polyline: String,
    val distanceM: Double,
    val durationS: Double,
    val createdAt: Instant,
) {
    init {
        require(version > 0) { "version must be positive, was $version" }
        require(distanceM >= 0 && durationS >= 0) { "A route cannot have a negative length or duration" }
    }
}
