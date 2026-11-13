package veeci.practicing.rts.routing.domain

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The smallest latitude and longitude box around a graph's nodes. The planner uses it to tell "this point is in
 * another city" (OUTSIDE_COVERAGE) apart from "this point is in the city but far from a road" (NO_ROAD_NEARBY).
 */
class GeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
) {
    /**
     * Whether [point] lies inside the box grown by [marginM] on every side. The margin is converted to degrees
     * at the box's middle latitude. Over one city the error of that is a few metres, which a 1 km margin hides.
     */
    fun contains(
        point: GeoPoint,
        marginM: Double,
    ): Boolean {
        if (minLat > maxLat) return false // the box of an empty graph contains nothing
        val marginLat = Math.toDegrees(marginM / Haversine.EARTH_RADIUS_M)
        val marginLng = marginLat / cos(Math.toRadians((minLat + maxLat) / 2))
        return point.lat in (minLat - marginLat)..(maxLat + marginLat) &&
            point.lng in (minLng - marginLng)..(maxLng + marginLng)
    }

    companion object {
        fun of(graph: RoadGraph): GeoBounds {
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLng = Double.POSITIVE_INFINITY
            var maxLng = Double.NEGATIVE_INFINITY
            for (v in 0 until graph.nodeCount) {
                minLat = min(minLat, graph.nodeLat[v])
                maxLat = max(maxLat, graph.nodeLat[v])
                minLng = min(minLng, graph.nodeLng[v])
                maxLng = max(maxLng, graph.nodeLng[v])
            }
            return GeoBounds(minLat, maxLat, minLng, maxLng)
        }
    }
}
