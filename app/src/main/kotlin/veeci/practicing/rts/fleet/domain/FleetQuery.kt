package veeci.practicing.rts.fleet.domain

import java.util.UUID
import kotlin.time.Instant

/** A map area. Edges count as inside. It may not cross the antimeridian: [minLng] is at most [maxLng]. */
data class Bbox(
    val minLng: Double,
    val minLat: Double,
    val maxLng: Double,
    val maxLat: Double,
) {
    init {
        require(minLng in -MAX_LNG..MAX_LNG && maxLng in -MAX_LNG..MAX_LNG) { "Longitudes are -180 to 180" }
        require(minLat in -MAX_LAT..MAX_LAT && maxLat in -MAX_LAT..MAX_LAT) { "Latitudes are -90 to 90" }
        require(minLng <= maxLng && minLat <= maxLat) { "The minimum comes first; an area across the antimeridian is not supported" }
    }

    private companion object {
        const val MAX_LNG = 180.0
        const val MAX_LAT = 90.0
    }
}

/** Which drivers to list; a null field does not filter. */
data class FleetFilter(
    val online: Boolean? = null,
    val onTrip: Boolean? = null,
    /** Part of the display name (any case), or a whole driver id. */
    val q: String? = null,
    val bbox: Bbox? = null,
)

/** A position in the driver list, ordered by last seen (newest first, never-seen last), then driver id. */
data class FleetCursor(
    val lastSeenAt: Instant?,
    val driverId: UUID,
)

/** The headline numbers of the fleet. */
data class FleetCounts(
    val online: Int,
    val onTrip: Int,
    val disabled: Int,
)
