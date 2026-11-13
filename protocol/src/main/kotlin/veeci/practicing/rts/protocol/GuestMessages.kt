package veeci.practicing.rts.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// Messages of the guest socket (/ws/v1/guest): a guest watches one trip live.
// Errors use ErrorMessage, the same `error` message as on the driver socket.

@Serializable
sealed interface GuestClientMessage

/** Start watching [tripId]. One trip per socket. */
@Serializable
@SerialName("subscribe")
data class Subscribe(
    val tripId: String,
) : GuestClientMessage

@Serializable
sealed interface GuestServerMessage

/** Watching started; [trip] is the trip as it is now. Live messages follow. */
@Serializable
@SerialName("subscribed")
data class Subscribed(
    val trip: GuestTrip,
) : GuestServerMessage

@Serializable
data class GuestTrip(
    val tripId: String,
    val status: String,
    val pickup: Point,
    val destination: Point,
    val driverId: String? = null,
    val updatedAt: Instant,
    /**
     * The route the driver follows now, the one `trip_progress` measures on: driver to pickup before the ride,
     * pickup to destination during it. Absent when there is none. Added in phase 7.
     */
    val route: GuestRoute? = null,
)

/** A planned road route. */
@Serializable
data class GuestRoute(
    /** TRIP (pickup to destination) or APPROACH (driver to pickup). */
    val kind: String,
    /** Grows by 1 with each new plan of the same kind; `trip_progress.routeVersion` names the one it measures on. */
    val routeVersion: Int,
    /** Google Encoded Polyline, precision 5, as Android's `PolyUtil.decode` reads it. */
    val polyline: String,
    val distanceM: Double,
    val durationS: Double,
)

@Serializable
data class Point(
    val lat: Double,
    val lng: Double,
)

/** Where the driver is now. */
@Serializable
@SerialName("driver_position")
data class DriverPosition(
    val tripId: String,
    val lat: Double,
    val lng: Double,
    val bearingDeg: Double? = null,
    val speedMps: Double? = null,
    val recordedAt: Instant,
) : GuestServerMessage

/** How far the driver is from [target] (PICKUP or DESTINATION) and when they arrive. */
@Serializable
@SerialName("trip_progress")
data class TripProgress(
    val tripId: String,
    val target: String,
    val remainingM: Double,
    val etaS: Double,
    /** ROUTE when measured along the planned road, STRAIGHT_LINE when the trip has no route for this stage. */
    val basis: String,
    /** With basis ROUTE: the version of the route the distance is measured on. */
    val routeVersion: Int? = null,
) : GuestServerMessage

/**
 * Something happened on the trip. [event] says what: [STATUS_CHANGED] (the trip moved to [status]) or a moment
 * such as [DRIVER_ARRIVING]. [status] is always the trip's stage at that time. New [event] values may be added
 * in a later server, so a client ignores values it does not know. After a final stage (COMPLETED, CANCELLED)
 * the server closes the socket with 1000.
 */
@Serializable
@SerialName("trip_event")
data class TripEvent(
    val tripId: String,
    val event: String,
    val status: String,
    val at: Instant,
    /** DRIVER_ARRIVING: the straight-line distance to the pickup, in meters. */
    val remainingM: Double? = null,
    /** DRIVER_LOW_BATTERY: the battery level of the driver's phone, 0 to 100. */
    val levelPercent: Int? = null,
    /** DRIVER_STALE: when the server last heard from the driver's phone. */
    val lastSeenAt: Instant? = null,
) : GuestServerMessage {
    companion object {
        const val STATUS_CHANGED = "status_changed"
        const val DRIVER_ARRIVING = "driver_arriving"
        const val DRIVER_LOW_BATTERY = "driver_low_battery"
        const val DRIVER_STALE = "driver_stale"
        const val DRIVER_BACK = "driver_back"
        const val OFF_ROUTE = "off_route"
    }
}

/**
 * The driver follows a new route from now on (phase 7): draw it instead of the old one. `trip_progress` messages
 * with this [routeVersion] measure along it. [reason] says why; a client ignores values it does not know.
 */
@Serializable
@SerialName("route_updated")
data class RouteUpdated(
    val tripId: String,
    /** TRIP (pickup to destination) or APPROACH (driver to pickup). */
    val kind: String,
    val routeVersion: Int,
    /** Google Encoded Polyline, precision 5. */
    val polyline: String,
    val distanceM: Double,
    val durationS: Double,
    val reason: String,
) : GuestServerMessage {
    companion object {
        const val OFF_ROUTE = "off_route"
    }
}
