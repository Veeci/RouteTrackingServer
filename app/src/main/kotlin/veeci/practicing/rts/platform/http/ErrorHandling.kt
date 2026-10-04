package veeci.practicing.rts.platform.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.LoggerFactory
import veeci.practicing.rts.shared.DomainException
import java.util.UUID

private val log = LoggerFactory.getLogger("veeci.practicing.rts.platform.http.ErrorHandling")

/** Every failure, wherever it happens, leaves the server as a [Problem]. */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        // A business rule said no: expected, so no stack trace in the logs.
        exception<DomainException> { call, e ->
            call.respondProblem(call.problem(e.code.name, ErrorCatalog.status(e.code.category), e.message))
        }

        // Unreadable body or parameters (bad JSON, wrong types). The parser's message can name internal
        // classes, so the client gets a fixed sentence and the details stay in the debug log.
        exception<BadRequestException> { call, e ->
            log.debug("Malformed request on {}: {}", call.request.path(), e.message)
            call.respondProblem(call.problem(PlatformError.MALFORMED_REQUEST, "The request body or parameters could not be read."))
        }

        // Anything else is a bug. Full details go to the log under a traceId; the client only gets that id.
        exception<Throwable> { call, cause ->
            val problem =
                call.problem(
                    PlatformError.INTERNAL_ERROR,
                    "Something went wrong on our side. Quote the traceId when reporting it.",
                )
            log.error("Unhandled error on {} {} (traceId={})", call.request.httpMethod.value, call.request.path(), problem.traceId, cause)
            call.respondProblem(problem)
        }

        // No route matched the method and path.
        unhandled { call ->
            call.respondProblem(call.problem(PlatformError.NOT_FOUND, "Nothing exists at this method and path."))
        }
    }
}

private fun ApplicationCall.problem(
    code: String,
    status: HttpStatusCode,
    detail: String?,
) = Problem(
    type = ErrorCatalog.type(code),
    title = ErrorCatalog.title(code),
    status = status.value,
    detail = detail,
    instance = request.path(),
    code = code,
    traceId = callId ?: UUID.randomUUID().toString(), // the request id once CallId is installed (step 6)
)

private fun ApplicationCall.problem(
    error: PlatformError,
    detail: String,
) = problem(error.name, error.status, detail)
