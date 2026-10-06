package veeci.practicing.rts.tracking.domain

import java.util.UUID

/** Identifies one tracking session. The SDK generates it when tracking starts, and it survives reconnects. */
@JvmInline
value class SessionId(
    val value: UUID,
)

@JvmInline
value class DeviceId(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "A device id is 1 to $MAX_LENGTH characters" }
    }

    companion object {
        const val MAX_LENGTH = 128
    }
}
