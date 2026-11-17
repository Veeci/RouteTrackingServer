package veeci.practicing.rts.tracking.application.api

import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.shared.UserId
import java.util.UUID

/**
 * The driver sockets of this instance, as other contexts may use them. Device control sends commands through it
 * (phase 5) and the trip context sends routes (phase 7); both register for "a driver connected". Tracking knows
 * nothing of either: the dependency goes one way only.
 */
interface DriverLink {
    /**
     * Sends [message] (a `command`, a `route_updated`) to every socket of [driver] on this instance. False when the
     * driver has none here (offline, or connected to another instance): the sender then resends it after the
     * driver's next `welcome`.
     */
    suspend fun send(
        driver: UserId,
        message: ServerMessage,
    ): Boolean

    /** Closes every socket of [driver], on every instance, with 4403 (phase 11: the driver was disabled). */
    suspend fun disconnect(driver: UserId)

    /** [listener] runs right after a driver's `welcome`, before the socket handles its next message. */
    fun onConnected(listener: suspend (UserId) -> Unit)

    /**
     * [listener] runs for each `command_ack`, with the driver, the command id and whether it was applied. A
     * DomainException it throws becomes an `error` frame (for example COMMAND_NOT_FOUND); the session stays open.
     */
    fun onCommandAck(listener: suspend (driver: UserId, commandId: UUID, applied: Boolean) -> Unit)
}

/**
 * The driver sockets of this instance only, plus a signal when a driver's first socket here opens or the last one
 * closes. The Redis link (phase 10) uses it to listen for messages to drivers it holds.
 */
interface LocalDriverLink : DriverLink {
    fun onPresenceChanged(listener: suspend (driver: UserId, here: Boolean) -> Unit)
}
