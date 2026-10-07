package veeci.practicing.rts.shared

import java.util.UUID

/** Identifies a user account. The identity context issues it; every context refers to users by it. */
@JvmInline
value class UserId(
    val value: UUID,
)

enum class Role { GUEST, DRIVER, ADMIN }

/**
 * Who is calling: the authenticated user and their role. Inbound adapters build it from the access token
 * and pass it to the services. Routes only authenticate; services decide what this actor may do.
 */
data class Actor(
    val userId: UserId,
    val role: Role,
)
