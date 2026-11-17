package veeci.practicing.rts.identity.adapter.inbound.web

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import veeci.practicing.rts.identity.application.DriverAdminService
import veeci.practicing.rts.identity.domain.User
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.shared.UserId
import java.util.UUID
import kotlin.time.Instant

/** `POST /api/v1/admin/drivers/{driverId}/disable` and `/enable` (phase 11, ADMIN). Mount inside `authenticate`. */
fun Route.driverAdminRoutes(service: DriverAdminService) {
    post("/api/v1/admin/drivers/{driverId}/disable") {
        val body = call.receive<AccountChangeRequest>()
        call.respond(DriverAccountResponse.of(service.disable(call.driverId(), body.reason.orEmpty(), call.actor())))
    }
    post("/api/v1/admin/drivers/{driverId}/enable") {
        val body = call.receive<AccountChangeRequest>()
        call.respond(DriverAccountResponse.of(service.enable(call.driverId(), body.reason, call.actor())))
    }
}

private fun io.ktor.server.application.ApplicationCall.driverId(): UserId =
    parameters["driverId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(::UserId)
        ?: throw RequestInvalidException(listOf(FieldError("driverId", "must be a UUID")))

@Serializable
data class AccountChangeRequest(
    /** Required to disable; optional to enable. Kept in the audit log. */
    val reason: String? = null,
)

@Serializable
data class DriverAccountResponse(
    val driverId: String,
    val displayName: String,
    val disabled: Boolean,
    val disabledAt: Instant? = null,
    val disabledReason: String? = null,
) {
    companion object {
        fun of(user: User) =
            DriverAccountResponse(
                user.id.value.toString(),
                user.displayName,
                user.disabled != null,
                user.disabled?.at,
                user.disabled?.reason,
            )
    }
}
