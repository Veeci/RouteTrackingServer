package veeci.practicing.rts.platform.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import veeci.practicing.rts.platform.config.HttpConfig
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

    // A bucket of tokens per client IP, refilled every minute; a request costs one token.
    // Behind a proxy every request shares the proxy's IP: phase 1.5 adds the forwarded-header plugin.
    install(RateLimit) {
        global {
            rateLimiter(limit = config.rateLimitPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
}

private fun ApplicationCall.isWebSocketUpgrade() = request.headers[HttpHeaders.Upgrade].equals("websocket", ignoreCase = true)
