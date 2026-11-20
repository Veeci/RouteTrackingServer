package veeci.practicing.rts.testing

import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes

/**
 * Every route the app serves, as `GET /api/v1/trips/{id}`: the HTTP method and the path, without the selectors that
 * match no path segment (authentication, areas, ports, WebSocket headers). Completeness tests compare it with their
 * rows, so a new route cannot ship without one.
 */
fun RoutingNode.routeSignatures(): Set<String> = getAllRoutes().mapNotNull { it.signature() }.toSet()

/** [routeSignatures] of a running application. */
fun Application.routeSignatures(): Set<String> = plugin(RoutingRoot).routeSignatures()

/** `GET /api/v1/admin/drivers/{driverId}` for a route that ends in an HTTP method selector; null for other nodes. */
private fun RoutingNode.signature(): String? {
    val text = toString()
    val method = METHOD.find(text)?.groupValues?.get(1) ?: return null
    val path = text.replace(SELECTOR, "").trimEnd('/')
    return "$method $path"
}

private val METHOD = Regex("""\(method:(\w+)\)""")
private val SELECTOR = Regex("""/?\([^)]*\)""")
