package veeci.practicing.rts.routing.application.api

import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode
import veeci.practicing.rts.shared.geo.GeoPoint

/**
 * The routing context's public API: a road route between two points of the city.
 *
 * Other contexts import only this package. The graph, the index and the search algorithms stay internal to
 * routing, so they can change (contraction hierarchies, a binary graph cache) without touching any caller.
 */
interface RoutePlanner {
    /**
     * Plans a route from [from] to [to] that is cheapest under [metric].
     *
     * Runs on the CPU pool, not the caller's thread, and gives up after the configured time budget.
     * @throws veeci.practicing.rts.shared.DomainException with a [RoutingError] code when no route can be given.
     */
    suspend fun plan(
        from: GeoPoint,
        to: GeoPoint,
        metric: RouteMetric = RouteMetric.DISTANCE,
    ): PlannedRoute
}

/** What a route minimises. The public twin of the search's own metric, so callers never see routing internals. */
enum class RouteMetric {
    /** The shortest route in metres. */
    DISTANCE,

    /** The quickest route at each road's default speed. */
    TIME,
}

/**
 * A planned route. [polyline] starts exactly at the requested start and ends exactly at the requested end; the
 * points between are graph nodes. [distanceM] and [durationS] include the two short legs between the requested
 * points and the roads they snapped to.
 */
data class PlannedRoute(
    val polyline: List<GeoPoint>,
    val distanceM: Double,
    val durationS: Double,
    val metric: RouteMetric,
)

/** Why no route was given. The names are public API: clients branch on them. */
enum class RoutingError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** A point is outside the city the server has a map for (its bounding box plus a margin). */
    OUTSIDE_COVERAGE(ErrorCategory.INVALID),

    /** A point is inside the city but farther than the snap distance from every road, e.g. in a lake. */
    NO_ROAD_NEARBY(ErrorCategory.INVALID),

    /** Both points are near roads, but no legal path joins them (one-way streets, a road the map cut off). */
    NO_ROUTE_FOUND(ErrorCategory.INVALID),

    /** The road map has not finished loading. Readiness is DOWN meanwhile, so few requests should see this. */
    ROUTING_NOT_READY(ErrorCategory.UNAVAILABLE),

    /** The search ran past its time budget and was stopped. */
    ROUTING_TIMEOUT(ErrorCategory.UNAVAILABLE),
}
