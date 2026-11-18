package veeci.practicing.rts.platform.http

import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/**
 * Matches a path under [path] without consuming it, so the routes inside keep their full paths. A plugin installed on
 * the child route this selector creates then applies to those routes only (the admin CORS, the staging gate).
 */
internal class PathPrefixSelector(
    private val path: String,
) : RouteSelector() {
    private val prefix = path.split("/").filter { it.isNotEmpty() }

    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation =
        if (context.segments.drop(segmentIndex).take(prefix.size) == prefix) {
            RouteSelectorEvaluation.Transparent
        } else {
            RouteSelectorEvaluation.Failed
        }

    override fun toString() = "(area $path)"
}
