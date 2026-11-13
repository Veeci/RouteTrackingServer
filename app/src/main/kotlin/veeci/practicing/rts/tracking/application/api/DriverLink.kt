package veeci.practicing.rts.tracking.application.api

import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.shared.UserId
import java.util.UUID

/**
 * The driver sockets of this instance, as other contexts may use them (phase 5). Device control sends commands
 * through it and registers for "a driver connected" and "a device acked a command". Tracking knows nothing of
 * device control: the dependency goes one way only.
 */
interface DriverLink {
    /**
     * Sends [command] to every socket of [driver] on this instance. False when the driver has none here (offline,
     * or connected to another instance): the command then waits for the driver's next `welcome`.
     */
    suspend fun send(
        driver: UserId,
        command: Command,
    ): Boolean

    /** [listener] runs right after a driver's `welcome`, before the socket handles its next message. */
    fun onConnected(listener: suspend (UserId) -> Unit)

    /**
     * [listener] runs for each `command_ack`, with the driver, the command id and whether it was applied. A
     * DomainException it throws becomes an `error` frame (for example COMMAND_NOT_FOUND); the session stays open.
     */
    fun onCommandAck(listener: suspend (driver: UserId, commandId: UUID, applied: Boolean) -> Unit)
}
