package veeci.practicing.rts.routing.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import veeci.practicing.rts.routing.application.api.PlannedRoute
import veeci.practicing.rts.routing.application.api.RouteMetric
import veeci.practicing.rts.routing.application.api.RoutePlanner
import veeci.practicing.rts.routing.application.api.RoutingError
import veeci.practicing.rts.routing.domain.HighwayType
import veeci.practicing.rts.routing.domain.NodeMatch
import veeci.practicing.rts.routing.domain.search.Metric
import veeci.practicing.rts.routing.domain.search.ShortestPath
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class PlannerSettings(
    /** A point farther than this from every graph node has no road nearby. */
    val maxSnapDistanceM: Double = 300.0,
    /** A point farther than this outside the graph's bounding box is outside coverage. */
    val coverageMarginM: Double = 1_000.0,
    /** The most time one plan may take, search included. */
    val timeout: Duration = 500.milliseconds,
)

/**
 * Plans routes on the loaded [RoadGraph][veeci.practicing.rts.routing.domain.RoadGraph]:
 *
 * 1. A point outside the graph's box plus [PlannerSettings.coverageMarginM] is OUTSIDE_COVERAGE. This runs before
 *    snapping: such a point is also far from every road, and "wrong city" is the more useful answer.
 * 2. Each point snaps to its nearest node within [PlannerSettings.maxSnapDistanceM], or it is NO_ROAD_NEARBY.
 * 3. Two points on the same node need no search: the route is the straight line between them.
 * 4. Otherwise the search runs; no path is NO_ROUTE_FOUND.
 * 5. The polyline is the start point, the path's nodes, then the end point. Distance and duration add the two
 *    snap legs, driven at [SNAP_LEG_SPEED_MPS].
 */
class GraphRoutePlanner(
    private val graphs: RoadGraphHolder,
    private val search: ShortestPath,
    private val settings: PlannerSettings = PlannerSettings(),
) : RoutePlanner {
    override suspend fun plan(
        from: GeoPoint,
        to: GeoPoint,
        metric: RouteMetric,
    ): PlannedRoute {
        val loaded =
            graphs.current
                ?: throw DomainException(RoutingError.ROUTING_NOT_READY, "The road map is still loading. Retry in a few seconds.")
        // The timeout is started inside withContext on purpose: its clock is then the Default pool's real clock,
        // whatever dispatcher the caller runs on (a test's virtual clock would fire it at once).
        return withContext(Dispatchers.Default) {
            withTimeoutOrNull(settings.timeout) {
                val budget = coroutineContext
                // The search never suspends, so cancellation cannot reach it on its own. The checkpoint asks the
                // coroutine every 1024 settled nodes and throws once the timeout has cancelled it.
                plan(loaded, from, to, metric) { budget.ensureActive() }
            }
        } ?: throw DomainException(RoutingError.ROUTING_TIMEOUT, "Planning the route took longer than ${settings.timeout}.")
    }

    private fun plan(
        loaded: LoadedRoadGraph,
        from: GeoPoint,
        to: GeoPoint,
        metric: RouteMetric,
        checkpoint: () -> Unit,
    ): PlannedRoute {
        requireCovered(loaded, from, "from")
        requireCovered(loaded, to, "to")
        val start = snap(loaded, from, "from")
        val end = snap(loaded, to, "to")
        if (start.node == end.node) {
            val directM = Haversine.distanceM(from.lat, from.lng, to.lat, to.lng)
            return PlannedRoute(listOf(from, to), directM, directM / SNAP_LEG_SPEED_MPS, metric)
        }
        val found =
            search.search(loaded.graph, start.node, end.node, metric.toSearchMetric(), checkpoint)
                ?: throw DomainException(RoutingError.NO_ROUTE_FOUND, "No road route joins `from` and `to`.")
        val legsM = start.distanceM + end.distanceM
        val polyline =
            ArrayList<GeoPoint>(found.nodes.size + 2).apply {
                add(from)
                found.nodes.forEach { addIfNew(loaded.graph.point(it)) }
                addIfNew(to)
            }
        return PlannedRoute(polyline, found.costM + legsM, found.costS + legsM / SNAP_LEG_SPEED_MPS, metric)
    }

    private fun requireCovered(
        loaded: LoadedRoadGraph,
        point: GeoPoint,
        which: String,
    ) {
        if (!loaded.bounds.contains(point, settings.coverageMarginM)) {
            throw DomainException(RoutingError.OUTSIDE_COVERAGE, "`$which` is outside the area this server has a road map for.")
        }
    }

    private fun snap(
        loaded: LoadedRoadGraph,
        point: GeoPoint,
        which: String,
    ): NodeMatch =
        loaded.index.nearest(point, settings.maxSnapDistanceM)
            ?: throw DomainException(
                RoutingError.NO_ROAD_NEARBY,
                "No road within ${settings.maxSnapDistanceM.toInt()} m of `$which`.",
            )

    /** A point that equals the previous one adds nothing to a polyline, e.g. a start exactly on its node. */
    private fun MutableList<GeoPoint>.addIfNew(point: GeoPoint) {
        if (point != last()) add(point)
    }

    private fun RouteMetric.toSearchMetric(): Metric =
        when (this) {
            RouteMetric.DISTANCE -> Metric.DISTANCE
            RouteMetric.TIME -> Metric.TIME
        }

    companion object {
        /** The leg between a point and its road is a driveway or an alley: the slowest road class. */
        val SNAP_LEG_SPEED_MPS: Double = HighwayType.SERVICE.speedMps.toDouble()
    }
}
