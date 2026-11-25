package veeci.practicing.rts.platform.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeadersConfig
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import veeci.practicing.rts.platform.config.HttpConfig
import java.net.InetAddress
import kotlin.time.Duration.Companion.minutes

/** Checks every request must pass before a route runs: size, content and rate. */
fun Application.configureRequestGuards(config: HttpConfig) {
    // Refuse oversized bodies before reading them into memory. Not for WebSocket upgrades: a socket is a
    // long-lived stream, not one body (its limit is per frame, see WsConfig.maxFrameBytes), and the plugin's
    // stream wrapper holds small frames back instead of passing them on. Long.MAX_VALUE turns the plugin off.
    install(RequestBodyLimit) {
        bodyLimit { call -> if (call.isWebSocketUpgrade()) Long.MAX_VALUE else config.maxBodyBytes }
    }

    // One validator for every Validatable body. Ktor's own result type only carries plain strings, so a
    // failure is thrown as RequestInvalidException, which keeps the field names for the response.
    install(RequestValidation) {
        validate<Validatable> { body ->
            val errors = body.validate()
            if (errors.isNotEmpty()) throw RequestInvalidException(errors)
            ValidationResult.Valid
        }
    }

    // Behind a proxy on this machine every request comes from loopback. With the flag on, the address the proxy
    // writes into X-Forwarded-For becomes the client's, so the rate limit below counts each client on its own.
    // The admin port trusts its proxy without the flag (phase 12f): only the dashboard's nginx reaches it, from the
    // compose network, which is not loopback.
    if (config.trustForwardedHeaders || config.splitsAdminPort) install(XForwardedHeaders) { trustKnownProxiesOnly(config) }

    // A bucket of tokens per client IP, refilled every minute; a request costs one token.
    install(RateLimit) {
        global {
            rateLimiter(limit = config.rateLimitPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
}

/**
 * Uses the forwarded headers only from a known proxy. A client that connects directly could send any
 * `X-Forwarded-For`, so its headers are ignored. Of a list such as `spoofed, client`, the last entry wins: the trusted
 * proxy appended it, and the entries before it came from the client.
 *
 * Two kinds of proxy are known:
 * - With [HttpConfig.trustForwardedHeaders], a peer on loopback, that is, a proxy on this machine (Funnel, T1b.4).
 * - With an admin port, any peer that reached that port. Staging publishes it on loopback only, and on the compose
 *   network only the dashboard's nginx calls it (phase 12f). nginx sends exactly one address, the operator's.
 */
private fun XForwardedHeadersConfig.trustKnownProxiesOnly(config: HttpConfig) {
    extractEdgeProxy { origin, headers ->
        val viaAdminPort = config.splitsAdminPort && origin.localPort == config.adminPort
        val viaLoopbackProxy = config.trustForwardedHeaders && isLoopback(origin.remoteAddress)
        if (viaAdminPort || viaLoopbackProxy) {
            headers.forHeader.lastEntry()?.let { client ->
                origin.remoteHost = client
                origin.remoteAddress = client
            }
            headers.protoHeader.lastEntry()?.let { origin.scheme = it }
        }
    }
}

private fun String?.lastEntry(): String? =
    this
        ?.split(',')
        ?.map { it.trim() }
        ?.lastOrNull { it.isNotEmpty() }

/**
 * An IPv4 literal, or an IPv6 one (the only kind with a colon). [InetAddress.getByName] parses either without a DNS
 * lookup. A host name never matches, so it is never looked up.
 */
private val IP_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*""")

internal fun isLoopback(address: String): Boolean =
    address == "localhost" ||
        (address.matches(IP_LITERAL) && runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false))

private fun ApplicationCall.isWebSocketUpgrade() = request.headers[HttpHeaders.Upgrade].equals("websocket", ignoreCase = true)
