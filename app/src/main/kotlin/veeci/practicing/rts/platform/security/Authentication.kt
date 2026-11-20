package veeci.practicing.rts.platform.security

import io.ktor.http.HttpHeaders
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import veeci.practicing.rts.platform.http.PlatformError
import veeci.practicing.rts.platform.http.respondPlatformProblem
import veeci.practicing.rts.shared.Actor

/** The name of the token check; wrap a route in `authenticate(AUTH_JWT) { ... }` to require a valid token. */
const val AUTH_JWT = "jwt"

/** The name of the ticket check (phase 11c): only the admin sockets use it, the fleet and the trip one (12a). */
const val AUTH_TICKET = "ws-ticket"

/** The authenticated caller, as the Authentication plugin keeps it for the rest of the call. */
data class ActorPrincipal(
    val actor: Actor,
)

/**
 * Checks the access token of every route inside `authenticate(AUTH_JWT)`. The token comes in the
 * `Authorization: Bearer <token>` header. A WebSocket client that cannot set headers (a browser) may send it
 * as `?token=` instead; request logs contain only the path, never the query, so the token is not logged.
 */
fun Application.configureAuthentication(
    tokens: AccessTokens,
    tickets: WsTickets,
) {
    install(Authentication) {
        register(TicketAuthentication(tickets))
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

/**
 * Checks `?ticket=` from [WsTickets] and nothing else: no header, no `?token=`. A browser's admin sockets use it, so the
 * access token never appears in a URL. A missing, used or expired ticket is answered 401 before the upgrade, like a
 * missing token on the other sockets.
 */
private class TicketAuthentication(
    private val tickets: WsTickets,
) : AuthenticationProvider(Config(AUTH_TICKET)) {
    private class Config(
        name: String,
    ) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val actor =
            context.call.request.queryParameters[TICKET_PARAMETER]
                ?.let { tickets.redeem(it) }
        if (actor != null) {
            context.principal(ActorPrincipal(actor))
            return
        }
        context.challenge(AUTH_TICKET, AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
            call.respondPlatformProblem(
                PlatformError.UNAUTHORIZED,
                "A valid, unused ticket from POST /api/v1/admin/ws-tickets is required.",
            )
            challenge.complete()
        }
    }
}

/** The caller of a route inside `authenticate(AUTH_JWT)`. */
fun ApplicationCall.actor(): Actor = checkNotNull(principal<ActorPrincipal>()) { "The route is not inside authenticate(AUTH_JWT)" }.actor

private const val TOKEN_PARAMETER = "token"
private const val TICKET_PARAMETER = "ticket"

// A malformed Authorization header counts as no token, like in Ktor's default.
private fun ApplicationCall.bearerHeader(): HttpAuthHeader? =
    try {
        request.parseAuthorizationHeader()
    } catch (_: IllegalArgumentException) {
        null
    }

private fun bearer(token: String) = HttpAuthHeader.Single(AuthScheme.Bearer, token)
