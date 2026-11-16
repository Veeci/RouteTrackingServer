package veeci.practicing.rts.live.application.api

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Instant

/** Something that guests watching a trip should see now. Positions and progress are not stored: the latest wins. */
sealed interface LiveUpdate {
    val tripId: UUID
}

data class DriverPositionUpdate(
    override val tripId: UUID,
    val point: GeoPoint,
    val bearingDeg: Double?,
    val speedMps: Double?,
    val recordedAt: Instant,
) : LiveUpdate

data class TripProgressUpdate(
    override val tripId: UUID,
    /** "PICKUP" while the driver comes to the guest, "DESTINATION" during the ride. */
    val target: String,
    val remainingM: Double,
    val etaS: Double,
    /** How [remainingM] was measured: "ROUTE" along the planned road, or "STRAIGHT_LINE" when there is no route. */
    val basis: String,
    /** The version of the route [remainingM] is measured on; null for "STRAIGHT_LINE". */
    val routeVersion: Int? = null,
) : LiveUpdate

data class TripStatusUpdate(
    override val tripId: UUID,
    val status: TripStatus,
    val at: Instant,
) : LiveUpdate

/** The driver follows a new route from now on (phase 7b: a re-route). */
data class RouteUpdate(
    override val tripId: UUID,
    /** TRIP or APPROACH. */
    val kind: String,
    val version: Int,
    /** Google Encoded Polyline, precision 5. */
    val polyline: String,
    val distanceM: Double,
    val durationS: Double,
    /** Why, as the trip context stored it, for example OFF_ROUTE. */
    val reason: String,
) : LiveUpdate

/** Something happened on the trip that is not a change of stage, for example "driver arriving". */
data class TripMomentUpdate(
    override val tripId: UUID,
    /** The moment's kind, for example DRIVER_ARRIVING. */
    val kind: String,
    /** The trip's stage when it happened. */
    val status: TripStatus,
    val at: Instant,
    /** DRIVER_ARRIVING: the straight-line distance to the pickup. */
    val remainingM: Double? = null,
    /** DRIVER_LOW_BATTERY: the battery level of the driver's phone. */
    val levelPercent: Int? = null,
    /** DRIVER_STALE: when the driver's phone was last heard. */
    val lastSeenAt: Instant? = null,
) : LiveUpdate

/** The live context's public API: other contexts hand it updates; it delivers them to the watching guests. */
interface LiveUpdates {
    /** Never waits: a slow guest loses old updates instead of slowing down the caller. */
    fun publish(update: LiveUpdate)
}
