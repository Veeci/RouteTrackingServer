package veeci.practicing.rts.platform.security

import io.ktor.http.HttpHeaders
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import veeci.practicing.rts.platform.http.PlatformError
import veeci.practicing.rts.platform.http.respondPlatformProblem
import veeci.practicing.rts.shared.Actor

/** The name of the token check; wrap a route in `authenticate(AUTH_JWT) { ... }` to require a valid token. */
const val AUTH_JWT = "jwt"

/** The authenticated caller, as the Authentication plugin keeps it for the rest of the call. */
data class ActorPrincipal(
    val actor: Actor,
)

/**
 * Checks the access token of every route inside `authenticate(AUTH_JWT)`. The token comes in the
 * `Authorization: Bearer <token>` header. A WebSocket client that cannot set headers (a browser) may send it
 * as `?token=` instead; request logs contain only the path, never the query, so the token is not logged.
 */
fun Application.configureAuthentication(tokens: AccessTokens) {
    install(Authentication) {
        jwt(AUTH_JWT) {
            verifier(tokens.verifier)
            authHeader { call -> call.bearerHeader() ?: call.request.queryParameters[TOKEN_PARAMETER]?.let(::bearer) }
            validate { credential -> tokens.actorOf(credential.payload)?.let(::ActorPrincipal) }
            challenge { _, _ ->
                call.response.headers.append(HttpHeaders.WWWAuthenticate, AuthScheme.Bearer)
                call.respondPlatformProblem(PlatformError.UNAUTHORIZED, "A valid access token is required.")
            }
        }
    }
}

/** The caller of a route inside `authenticate(AUTH_JWT)`. */
fun ApplicationCall.actor(): Actor = checkNotNull(principal<ActorPrincipal>()) { "The route is not inside authenticate(AUTH_JWT)" }.actor

private const val TOKEN_PARAMETER = "token"

// A malformed Authorization header counts as no token, like in Ktor's default.
private fun ApplicationCall.bearerHeader(): HttpAuthHeader? =
    try {
        request.parseAuthorizationHeader()
    } catch (_: IllegalArgumentException) {
        null
    }

private fun bearer(token: String) = HttpAuthHeader.Single(AuthScheme.Bearer, token)
