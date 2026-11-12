package veeci.practicing.rts.devicecontrol.domain.event

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Instant

/** A command for a driver's device was stored. The device-delivery listener (phase 5b) sends it if it can. */
data class CommandIssued(
    val commandId: UUID,
    val driverId: UUID,
    /** SET_CONSTRAINT, REGISTER_GEOFENCES or FLUSH_QUEUE. */
    val kind: String,
    val at: Instant,
)

/** [CommandIssued] as JSON in the outbox. Fields may be added; a change to an existing field needs `...v2`. */
@Serializable
data class CommandIssuedV1(
    val commandId: String,
    val driverId: String,
    val kind: String,
    val at: Instant,
) {
    fun toEvent() = CommandIssued(UUID.fromString(commandId), UUID.fromString(driverId), kind, at)

    companion object {
        const val TYPE = "devicecontrol.CommandIssued.v1"

        fun of(event: CommandIssued) = CommandIssuedV1(event.commandId.toString(), event.driverId.toString(), event.kind, event.at)
    }
}
