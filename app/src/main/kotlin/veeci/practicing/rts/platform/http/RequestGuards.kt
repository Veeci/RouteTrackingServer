package veeci.practicing.rts.platform.http

import io.ktor.server.application.Application
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
    // Refuse oversized bodies before reading them into memory.
    install(RequestBodyLimit) {
        bodyLimit { config.maxBodyBytes }
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
