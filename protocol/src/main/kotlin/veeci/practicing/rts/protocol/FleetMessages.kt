package veeci.practicing.rts.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// Messages of the admin fleet socket (/ws/v1/admin/fleet, phase 11c): staff watch every driver inside a map area.
// Errors use ErrorMessage, the same `error` message as on the other sockets.

@Serializable
sealed interface FleetClientMessage

/**
 * Watch the drivers inside [bbox] that match [filters]. A new `fleet_subscribe` replaces the previous one: the server
 * answers with a fresh snapshot (or clusters) and sends no more changes for the old area.
 */
@Serializable
@SerialName("fleet_subscribe")
data class FleetSubscribe(
    /** `[minLng, minLat, maxLng, maxLat]`, edges inside. It may not cross the antimeridian. */
    val bbox: List<Double>,
    val filters: FleetFilters = FleetFilters(),
) : FleetClientMessage

/** A null field does not filter. */
@Serializable
data class FleetFilters(
    /** Only drivers heard from within the stale time (true), or only the others (false). */
    val online: Boolean? = null,
    /** Only drivers on a trip (true), or only the others (false). */
    val onTrip: Boolean? = null,
)

@Serializable
sealed interface FleetServerMessage

/** Every matching driver in the area now. Deltas follow, one per tick. */
@Serializable
@SerialName("fleet_snapshot")
data class FleetSnapshot(
    val drivers: List<DriverMarker>,
    val at: Instant,
) : FleetServerMessage

/**
 * What changed since the last message, sent every tick (2 s), empty when nothing changed. An upsert replaces the
 * driver's marker or adds it; a remove takes it off the map (it left the area or stopped matching the filters).
 */
@Serializable
@SerialName("fleet_delta")
data class FleetDelta(
    val upserts: List<DriverMarker>,
    val removes: List<String>,
    val at: Instant,
) : FleetServerMessage

/**
 * The area holds more matching drivers than the server sends one by one (5 000): counts per grid cell instead, sent
 * fresh every tick, with no deltas. When the area holds fewer again, a `fleet_snapshot` follows and deltas resume.
 */
@Serializable
@SerialName("fleet_cluster")
data class FleetCluster(
    val cells: List<FleetCell>,
    /** The side of one grid cell, in degrees. */
    val cellDeg: Double,
    /** Matching drivers in the area, the sum of the counts. */
    val total: Int,
    val at: Instant,
) : FleetServerMessage

/** One grid cell with drivers in it; [lat] and [lng] are their mean position, where a map draws the count. */
@Serializable
data class FleetCell(
    val lat: Double,
    val lng: Double,
    val count: Int,
)

/** One driver on the map. */
@Serializable
data class DriverMarker(
    val driverId: String,
    val lat: Double,
    val lng: Double,
    /** Absent when the newest fix had no bearing. */
    val bearingDeg: Double? = null,
    /** Heard from within the stale time (60 s). */
    val online: Boolean,
    /** The stage of the driver's current trip; absent when they are not on one. */
    val tripStatus: String? = null,
    /** Absent until the phone sends a device status. */
    val batteryPct: Int? = null,
    /** When the phone last told the server something, by the phone's clock. */
    val lastSeenAt: Instant,
)
