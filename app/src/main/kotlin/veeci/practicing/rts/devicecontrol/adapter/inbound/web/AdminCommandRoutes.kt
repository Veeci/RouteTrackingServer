package veeci.practicing.rts.devicecontrol.adapter.inbound.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import veeci.practicing.rts.devicecontrol.application.CommandService
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.shared.UserId
import java.util.UUID

/**
 * Admin API for device commands (`/api/v1/admin/drivers/{driverId}/commands`, contract in openapi/rts-v1.yaml).
 * Mount inside `authenticate(AUTH_JWT)`. The service checks that the caller is an admin.
 */
fun Route.adminCommandRoutes(service: CommandService) {
    route("/api/v1/admin/drivers/{driverId}/commands") {
        // Stored at once; the device gets it when it is (or next gets) connected. 202: not applied yet.
        post {
            val body = call.receive<IssueCommandBody>() // validated (400) before this line returns
            val command = service.issue(call.driverId(), body.toPayload(), call.actor())
            call.respond(HttpStatusCode.Accepted, IssuedCommandResponse(command.id.value.toString()))
        }

        // The driver's newest commands with their statuses, newest first.
        get {
            val commands = service.list(call.driverId(), call.actor(), LIST_LIMIT)
            call.respond(CommandListResponse(commands.map(CommandResponse::of)))
        }
    }
}

private const val LIST_LIMIT = 50

private fun ApplicationCall.driverId(): UserId {
    val uuid = parameters["driverId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    return UserId(uuid ?: throw RequestInvalidException(listOf(FieldError("driverId", "must be a UUID"))))
}
