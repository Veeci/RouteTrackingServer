package veeci.practicing.rts.fleet.domain

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Instant

/** One driver on the live map (phase 11c). */
data class FleetMarker(
    val driverId: UUID,
    val lat: Double,
    val lng: Double,
    val bearingDeg: Double?,
    val online: Boolean,
    /** The stage of the current trip; null when the driver is not on one. */
    val tripStatus: String?,
    val batteryPct: Int?,
    /** When the newest position or status was measured, by the phone's clock. */
    val lastSeenAt: Instant,
    /** When the server last heard from the driver; [online] is judged on it. Null until it has. */
    val heardAt: Instant?,
)

/** A grid cell of the live map with [count] drivers; [lat] and [lng] are their mean position. */
data class MapCell(
    val lat: Double,
    val lng: Double,
    val count: Int,
)

/** What one subscriber of the live map watches: an area, and optionally only online drivers or drivers on a trip. */
data class MapView(
    val bbox: Bbox,
    val online: Boolean? = null,
    val onTrip: Boolean? = null,
) {
    /** The same view as a driver list filter, for the snapshot and cluster queries. */
    val filter: FleetFilter get() = FleetFilter(online = online, onTrip = onTrip, bbox = bbox)

    /** The side of a cluster cell: the area's longer side cut in [CELLS_PER_SIDE], so a map shows about 16 × 16 cells. */
    val cellDeg: Double
        get() = (maxOf(bbox.maxLng - bbox.minLng, bbox.maxLat - bbox.minLat) / CELLS_PER_SIDE).coerceAtLeast(MIN_CELL_DEG)

    /**
     * The driver's marker when this view shows them at [now], or null: no position yet, outside the area (edges are
     * inside, as in the SQL `BETWEEN`), or not matching a filter.
     */
    fun markerOf(
        driver: FleetDriver,
        now: Instant,
        staleAfter: Duration,
    ): FleetMarker? {
        val online = driver.isOnline(now, staleAfter)
        val position = driver.position?.takeIf { inside(it) && matches(online, driver.onTrip) } ?: return null
        return FleetMarker(
            driverId = driver.driverId,
            lat = position.lat,
            lng = position.lng,
            bearingDeg = position.bearingDeg,
            online = online,
            tripStatus = driver.trip?.takeIf { driver.onTrip }?.status,
            batteryPct = driver.device?.batteryPct,
            lastSeenAt = driver.lastSeenAt ?: position.at,
            heardAt = driver.heardAt,
        )
    }

    private fun inside(position: DriverPosition): Boolean =
        position.lat in bbox.minLat..bbox.maxLat && position.lng in bbox.minLng..bbox.maxLng

    private fun matches(
        isOnline: Boolean,
        isOnTrip: Boolean,
    ): Boolean = (online == null || online == isOnline) && (onTrip == null || onTrip == isOnTrip)

    private companion object {
        const val CELLS_PER_SIDE = 16.0

        /** About 11 m: an area of one point still gets a cell size the SQL can divide by. */
        const val MIN_CELL_DEG = 0.0001
    }
}

/** What changed for one subscriber since its last message. */
data class MarkerChanges(
    val upserts: List<FleetMarker>,
    val removes: List<UUID>,
)

/**
 * The markers one subscriber's map shows now: the last marker sent for each driver. A tick's rows are compared with
 * it, so a subscriber gets a driver again only when their marker changed, and a remove only for a driver it has.
 * Not thread-safe: its subscriber guards it.
 */
class SentMarkers {
    private val sent = LinkedHashMap<UUID, FleetMarker>()

    val size: Int get() = sent.size

    val markers: List<FleetMarker> get() = sent.values.toList()

    fun reset(markers: Collection<FleetMarker>) {
        sent.clear()
        markers.forEach { sent[it.driverId] = it }
    }

    fun clear() = sent.clear()

    /**
     * Compares [rows] (drivers that changed, or crossed the stale bound) with what was sent, records the result, and
     * returns it. A row the view shows with a marker that differs from the sent one is an upsert; a row the view no
     * longer shows, of a driver that was sent, is a remove; anything else (a duplicate, a driver never sent that the
     * view does not show) is dropped.
     */
    fun apply(
        rows: List<FleetDriver>,
        view: MapView,
        now: Instant,
        staleAfter: Duration,
    ): MarkerChanges {
        val upserts = mutableListOf<FleetMarker>()
        val removes = mutableListOf<UUID>()
        rows.forEach { driver ->
            val marker = view.markerOf(driver, now, staleAfter)
            when {
                marker != null && sent[driver.driverId] != marker -> {
                    sent[driver.driverId] = marker
                    upserts += marker
                }

                marker == null && sent.remove(driver.driverId) != null -> {
                    removes += driver.driverId
                }
            }
        }
        return MarkerChanges(upserts, removes)
    }
}
