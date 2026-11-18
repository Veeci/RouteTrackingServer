package veeci.practicing.rts.platform.security

import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.routing.Route
import veeci.practicing.rts.platform.config.MIN_STAGING_TOKEN_LENGTH
import veeci.practicing.rts.platform.config.StagingConfig
import veeci.practicing.rts.platform.http.PathPrefixSelector
import veeci.practicing.rts.platform.http.PlatformError
import veeci.practicing.rts.platform.http.respondPlatformProblem
import java.security.MessageDigest

/** Every dev route's path starts with this. */
const val DEV_PATH = "/api/v1/dev"

/** The header a tester sends with the staging access token. */
const val STAGING_TOKEN_HEADER = "X-Staging-Token"

/**
 * The routes in [build], which must live under [DEV_PATH], behind [StagingGate] (phase 1.5). Staging is on the
 * internet and has no login yet, so the dev token route needs a shared secret there. Every other route keeps its own
 * checks: the health routes and `/ws/v1/echo` stay open.
 */
fun Route.stagingGate(
    config: StagingConfig,
    build: Route.() -> Unit,
) {
    val token = requireNotNull(config.access.token) { "STAGING_ACCESS_TOKEN is not set" } // AppConfigLoader checks it first
    val area = createChild(PathPrefixSelector(DEV_PATH))
    area.install(StagingGate) { this.token = token.value }
    area.build()
}

class StagingGateConfig {
    var token: String = ""
}

/**
 * Answers 401 Problem unless [STAGING_TOKEN_HEADER] holds the configured token. `MessageDigest.isEqual` takes the
 * same time wherever the first wrong byte is, so the response time does not tell an attacker how much of a guess was
 * right. A plain `==` stops at the first difference.
 */
val StagingGate =
    createRouteScopedPlugin("StagingGate", ::StagingGateConfig) {
        val expected = pluginConfig.token.toByteArray(Charsets.UTF_8)
        require(expected.size >= MIN_STAGING_TOKEN_LENGTH) { "The staging token needs at least $MIN_STAGING_TOKEN_LENGTH characters" }
        onCall { call ->
            val given = call.request.headers[STAGING_TOKEN_HEADER]?.toByteArray(Charsets.UTF_8)
            if (given == null || !MessageDigest.isEqual(given, expected)) {
                call.respondPlatformProblem(PlatformError.UNAUTHORIZED, "A valid $STAGING_TOKEN_HEADER header is required.")
            }
        }
    }
