package veeci.practicing.rts.platform.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.options
import veeci.practicing.rts.platform.config.AdminConfig

/** Every admin route's path starts with this. */
const val ADMIN_PATH = "/api/v1/admin"

/**
 * Headers for browsers (phase 11c). Every response says `nosniff` (a browser must not guess a type other than the
 * Content-Type, so a JSON body never runs as script) and `no-referrer` (a link followed from the dashboard does not
 * tell the other site the page URL). Admin responses also say `no-store`: driver positions and names must not stay in
 * a shared or disk cache.
 */
fun Application.configureSecurityHeaders() {
    install(
        createApplicationPlugin("SecurityHeaders") {
            onCall { call ->
                call.response.headers.append(CONTENT_TYPE_OPTIONS, "nosniff")
                call.response.headers.append(REFERRER_POLICY, "no-referrer")
                if (call.request.path().isAdminPath()) call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            }
        },
    )
}

/**
 * The admin routes, with CORS for [AdminConfig.origins] when there are any. Only these routes answer a browser on
 * another origin; the driver and guest APIs never do. Credentials stay off: the dashboard sends its token in the
 * Authorization header, never in a cookie, so another site cannot make the browser call the API as the operator.
 *
 * The area's selector consumes no path segment, so the routes inside keep their full `/api/v1/admin/...` paths.
 */
fun Route.adminArea(
    config: AdminConfig,
    build: Route.() -> Unit,
) {
    val area = createChild(PathPrefixSelector(ADMIN_PATH))
    val origins =
        config.origins
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .toSet()
    if (origins.isNotEmpty()) {
        area.install(CORS) {
            allowOrigins { it in origins }
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.XRequestId)
            allowCredentials = false
            maxAgeInSeconds = PREFLIGHT_CACHE_S
        }
        // A route must match a preflight request for the route-scoped CORS plugin to see it. CORS answers every real
        // preflight itself; this handler only answers an OPTIONS request that is not one.
        area.options("$ADMIN_PATH/{...}") { call.respond(HttpStatusCode.NoContent) }
    }
    area.build()
}

private fun String.isAdminPath(): Boolean = this == ADMIN_PATH || startsWith("$ADMIN_PATH/")

private const val CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
private const val REFERRER_POLICY = "Referrer-Policy"

/** Browsers cache a preflight answer for at most 10 minutes (Chromium's limit is 2 hours, Firefox's 24). */
private const val PREFLIGHT_CACHE_S = 600L
