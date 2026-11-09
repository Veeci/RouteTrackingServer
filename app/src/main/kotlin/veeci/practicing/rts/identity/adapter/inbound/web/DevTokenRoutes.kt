package veeci.practicing.rts.identity.adapter.inbound.web

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import veeci.practicing.rts.identity.application.DevTokenService
import veeci.practicing.rts.identity.application.IssueDevToken
import veeci.practicing.rts.identity.domain.User
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.Validatable
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import java.util.UUID
import kotlin.time.Instant

/** `POST /api/v1/dev/tokens`: a user and a token for them. The caller mounts it only in dev and test. */
fun Route.devTokenRoutes(service: DevTokenService) {
    post("/api/v1/dev/tokens") {
        val body = call.receive<DevTokenRequest>() // checked by validate() below before the handler sees it
        val issued =
            service.issue(
                IssueDevToken(
                    userId = body.userId?.let { UserId(UUID.fromString(it)) },
                    role = Role.valueOf(body.role),
                    displayName = body.displayName ?: "${body.role.lowercase()}-${UUID.randomUUID().toString().substringBefore('-')}",
                ),
            )
        call.respond(
            DevTokenResponse(
                token = issued.token.token,
                userId =
                    issued.user.id.value
                        .toString(),
                role = issued.user.role.name,
                displayName = issued.user.displayName,
                expiresAt = issued.token.expiresAt,
            ),
        )
    }
}

@Serializable
data class DevTokenRequest(
    val role: String,
    val displayName: String? = null,
    /** An existing user to log in as; absent creates a new user. */
    val userId: String? = null,
) : Validatable {
    override fun validate(): List<FieldError> =
        buildList {
            if (Role.entries.none { it.name == role }) add(FieldError("role", "must be one of ${Role.entries.joinToString()}"))
            if (displayName != null && (displayName.isBlank() || displayName.length > User.MAX_NAME_LENGTH)) {
                add(FieldError("displayName", "must be 1 to ${User.MAX_NAME_LENGTH} characters"))
            }
            if (userId != null && runCatching { UUID.fromString(userId) }.isFailure) add(FieldError("userId", "must be a UUID"))
        }
}

@Serializable
data class DevTokenResponse(
    val token: String,
    val userId: String,
    val role: String,
    val displayName: String,
    val expiresAt: Instant,
)
