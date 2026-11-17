package veeci.practicing.rts.shared

import java.util.UUID

/** Identifies a user account. The identity context issues it; every context refers to users by it. */
@JvmInline
value class UserId(
    val value: UUID,
)

/**
 * What a user may do. OPERATOR (phase 11) runs the fleet day to day: reads every driver and trip and cancels trips.
 * ADMIN may also change drivers and devices, and reads the audit log.
 */
enum class Role {
    GUEST,
    DRIVER,
    OPERATOR,
    ADMIN,
    ;

    /** Back-office staff: sees every trip and driver. */
    val isStaff: Boolean get() = this == OPERATOR || this == ADMIN
}

/**
 * Who is calling: the authenticated user and their role. Inbound adapters build it from the access token
 * and pass it to the services. Routes only authenticate; services decide what this actor may do.
 */
data class Actor(
    val userId: UserId,
    val role: Role,
)
