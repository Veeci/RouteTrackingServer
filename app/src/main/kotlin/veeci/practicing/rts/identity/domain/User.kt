package veeci.practicing.rts.identity.domain

import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import kotlin.time.Instant

/** A person who uses the system, with the one role they act in. */
data class User(
    val id: UserId,
    val displayName: String,
    val role: Role,
    /** Set when an admin disabled the account (phase 11): a disabled driver cannot open a tracking session. */
    val disabled: Disabled? = null,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_NAME_LENGTH) {
            "A display name is 1 to $MAX_NAME_LENGTH characters"
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 100
    }
}

/** When and why an account was disabled. */
data class Disabled(
    val at: Instant,
    val reason: String,
)
