package veeci.practicing.rts.identity.domain.event

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Instant

/**
 * A driver account was created, renamed, disabled or enabled (phase 11). It carries the whole account, not the
 * change, so a reader that missed an earlier one still ends with the right state. The fleet context keeps its
 * driver list with it.
 */
data class DriverAccountChanged(
    val driverId: UUID,
    val displayName: String,
    val enabled: Boolean,
    val at: Instant,
)

/**
 * [DriverAccountChanged] as JSON in the outbox, under the event type [TYPE]. A contract with every reader: fields
 * may be added, but a change to an existing field needs a new type, `...v2`.
 */
@Serializable
data class DriverAccountChangedV1(
    val driverId: String,
    val displayName: String,
    val enabled: Boolean,
    val at: Instant,
) {
    fun toEvent() = DriverAccountChanged(UUID.fromString(driverId), displayName, enabled, at)

    companion object {
        const val TYPE = "identity.DriverAccountChanged.v1"

        fun of(event: DriverAccountChanged) = DriverAccountChangedV1(event.driverId.toString(), event.displayName, event.enabled, event.at)
    }
}
