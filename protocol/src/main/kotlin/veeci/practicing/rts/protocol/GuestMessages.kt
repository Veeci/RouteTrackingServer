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
    /** STRAIGHT_LINE until distances are measured along the road (phase 7). */
    val basis: String,
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
) : GuestServerMessage {
    companion object {
        const val STATUS_CHANGED = "status_changed"
        const val DRIVER_ARRIVING = "driver_arriving"
        const val DRIVER_LOW_BATTERY = "driver_low_battery"
    }
}
