package veeci.practicing.rts.platform.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.exceptions.JWTDecodeException
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
import io.ktor.util.AttributeKey
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
 *
 * Two kinds of token are accepted (phase 12g): the server's own HS256 tokens ([tokens]), and the identity provider's
 * RS256 tokens ([provider], null when `OIDC_ISSUER` is empty). The token's header and `iss` pick the verifier, and that
 * verifier then checks everything again with its own key, algorithm and issuer. So a token that claims the provider's
 * issuer but is signed with HS256 (an algorithm confusion attempt) meets the RS256 verifier and fails, and one that
 * claims RS256 with the server's issuer meets the HS256 verifier and fails.
 */
fun Application.configureAuthentication(
    tokens: AccessTokens,
    tickets: WsTickets,
    provider: ProviderTokens? = null,
) {
    install(Authentication) {
        register(TicketAuthentication(tickets))
        jwt(AUTH_JWT) {
            verifier { header -> verifierFor(header, tokens, provider) }
            authHeader { call -> call.bearerHeader() ?: call.request.queryParameters[TOKEN_PARAMETER]?.let(::bearer) }
            validate { credential ->
                val payload = credential.payload
                val actor =
                    if (provider != null && payload.issuer == provider.issuer) {
                        provider.actorOf(payload).also { if (it == null) attributes.put(NoStaffRole, Unit) }
                    } else {
                        tokens.actorOf(payload)
                    }
                actor?.let(::ActorPrincipal)
            }
            challenge { _, _ ->
                if (call.attributes.contains(NoStaffRole)) {
                    call.respondPlatformProblem(
                        PlatformError.NO_STAFF_ROLE,
                        "The identity provider gives this account no operator or admin role.",
                    )
                } else {
                    call.response.headers.append(HttpHeaders.WWWAuthenticate, AuthScheme.Bearer)
                    call.respondPlatformProblem(PlatformError.UNAUTHORIZED, "A valid access token is required.")
                }
            }
        }
    }
}

/** Set by `validate` when a provider token is valid but has no staff role: the challenge then answers 403. */
private val NoStaffRole = AttributeKey<Unit>("NoStaffRole")

/** The provider's verifier for an RS256 token with the provider's issuer, the server's own for anything else. */
private fun verifierFor(
    header: HttpAuthHeader,
    tokens: AccessTokens,
    provider: ProviderTokens?,
): JWTVerifier {
    if (provider == null || header !is HttpAuthHeader.Single) return tokens.verifier
    val decoded =
        try {
            JWT.decode(header.blob)
        } catch (_: JWTDecodeException) {
            return tokens.verifier
        }
    return if (decoded.algorithm == "RS256" && decoded.issuer == provider.issuer) provider.verifier else tokens.verifier
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
