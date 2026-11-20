package veeci.practicing.rts.platform.http

import io.ktor.server.routing.Route
import io.ktor.server.routing.localPort
import veeci.practicing.rts.platform.config.HttpConfig

/**
 * The routes staff use: the admin REST routes and the admin sockets (phase 12a). With [HttpConfig.adminPort] set they
 * answer only on that port, and on the public port no route matches them, so it answers 404. With 0 they answer on
 * the public port, as before.
 *
 * Ktor's `localPort` selector compares the port the connection reached on this server (`request.local`), not the
 * `origin` that the forwarded-header plugin rewrites, so a client cannot reach the admin routes by sending a header.
 * Routes outside this block answer on both ports: the admin port is a superset of the public one, because the
 * dashboard also reads trips and logs in with a dev token through it.
 */
fun Route.adminPlane(
    http: HttpConfig,
    build: Route.() -> Unit,
) {
    if (http.splitsAdminPort) localPort(http.adminPort, build) else build()
}
