package veeci.practicing.rts.routing.adapter.inbound.web

import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.routing.application.api.PlannedRoute
import veeci.practicing.rts.routing.application.api.RouteMetric
import veeci.practicing.rts.routing.application.api.RoutePlanner
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.PolylineCodec

/**
 * `GET /api/v1/routes?from=lat,lng&to=lat,lng&metric=distance|time` (contract in openapi/rts-v1.yaml).
 * Mount inside `authenticate(AUTH_JWT)`: one call costs up to 500 ms of CPU, so it is not open to anonymous
 * callers. Routing failures are DomainExceptions, which StatusPages turns into 422 or 503 Problems.
 */
fun Route.routeRoutes(planner: RoutePlanner) {
    get("/api/v1/routes") {
        val query = RouteQuery.parse(call.request.queryParameters)
        call.respond(RouteResponse.of(planner.plan(query.from, query.to, query.metric)))
    }
}

@Serializable
data class RouteResponse(
    /** Google Encoded Polyline, precision 5. */
    val polyline: String,
    val distanceM: Double,
    val durationS: Double,
    val metric: String,
) {
    companion object {
        fun of(route: PlannedRoute) =
            RouteResponse(PolylineCodec.encode(route.polyline), route.distanceM, route.durationS, route.metric.name.lowercase())
    }
}

private class RouteQuery(
    val from: GeoPoint,
    val to: GeoPoint,
    val metric: RouteMetric,
) {
    companion object {
        fun parse(parameters: Parameters): RouteQuery {
            val errors = mutableListOf<FieldError>()
            val from = point(parameters, "from", errors)
            val to = point(parameters, "to", errors)
            val metric = metric(parameters["metric"])
            if (metric == null) errors += FieldError("metric", "must be distance or time")
            if (errors.isNotEmpty()) throw RequestInvalidException(errors)
            return RouteQuery(checkNotNull(from), checkNotNull(to), checkNotNull(metric))
        }

        private fun point(
            parameters: Parameters,
            name: String,
            errors: MutableList<FieldError>,
        ): GeoPoint? {
            val point = parameters[name]?.let(::parsePoint)
            if (point == null) errors += FieldError(name, "must be lat,lng in degrees, e.g. 21.0285,105.8542")
            return point
        }

        /** Absent means DISTANCE; an unknown value is null. */
        private fun metric(text: String?): RouteMetric? =
            if (text == null) RouteMetric.DISTANCE else RouteMetric.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }

        /** `21.0285,105.8542`: latitude first, like the trip API's points. Null when it is not two valid degrees. */
        private fun parsePoint(text: String): GeoPoint? {
            val parts = text.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            val valid = text.count { it == ',' } == 1 && parts.size == 2 && GeoPoint.isValid(parts[0], parts[1])
            return if (valid) GeoPoint(parts[0], parts[1]) else null
        }
    }
}
