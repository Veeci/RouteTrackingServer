package veeci.practicing.rts.platform.observability

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.util.UUID

/** MDC key holding the request id; every log line written while handling a request carries it. */
const val REQUEST_ID_MDC = "requestId"

private const val MAX_REQUEST_ID_LENGTH = 64

/**
 * Gives every request an id, returns it in `X-Request-Id`, puts it on every log line written while
 * handling the request, and writes one summary line per request.
 */
fun Application.configureRequestLogging() {
    install(CallId) {
        // Keep the caller's id when it sends one, so a request can be followed across services.
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        // The id is copied into logs: an id with spaces, newlines or 10 kB of text is ignored and replaced.
        verify { id -> id.length <= MAX_REQUEST_ID_LENGTH && id.all(::isSafeIdChar) }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        callIdMdc(REQUEST_ID_MDC)
        // Probes and scrapes arrive every few seconds; logging them would bury real traffic.
        filter { call -> call.request.path().let { !it.startsWith("/health") && it != "/metrics" } }
        format { call ->
            "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()} in ${call.processingTimeMillis()} ms"
        }
        disableDefaultColors()
    }
}

private fun isSafeIdChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'
