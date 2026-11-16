package veeci.practicing.rts.trip.domain.summary

import veeci.practicing.rts.trip.domain.TripId
import kotlin.time.Instant

/** What a completed trip looked like (phase 8a). Stored once per trip; computing it again gives the same summary. */
data class TripSummary(
    val tripId: TripId,
    /** From the start of the ride to its end. */
    val ride: LegSummary,
    /** From accept to arrival at the pickup; null when the trip has no such window. Only the driver and admins see it. */
    val approach: LegSummary?,
    /** The first planned pickup-to-destination route; null when the trip has none. */
    val plannedDistanceM: Double?,
    /** How often the driver left the route (OffRoute moments). */
    val offRouteCount: Int,
    /** How often a new route was planned after that (OFF_ROUTE routes). Lower than [offRouteCount] when a re-plan failed. */
    val rerouteCount: Int,
    /** Rejected fixes during the ride, by reason. */
    val rejected: Map<String, Int>,
    /** True for a trip stored before stage times existed: the ride window is then the trip's whole life. */
    val approximate: Boolean,
    val computedAt: Instant,
)

/** One stretch of driving between two times. */
data class LegSummary(
    val distanceM: Double,
    /** The whole window, stops included. */
    val durationS: Long,
    /** Only the time spent moving at [LegSummarizer.MOVING_MPS] or faster. */
    val movingTimeS: Long,
    /** [distanceM] over [movingTimeS]; 0 when the driver never moved. */
    val avgSpeedMps: Double,
    /** The highest 3-point median speed, so one GPS spike cannot be the maximum. */
    val maxSpeedMps: Double,
    val pointCount: Int,
    val startedAt: Instant,
    val endedAt: Instant,
)
