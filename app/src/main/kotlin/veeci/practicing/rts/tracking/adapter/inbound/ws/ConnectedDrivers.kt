package veeci.practicing.rts.tracking.adapter.inbound.ws

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.ws.WsSession
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.DriverLink
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which drivers have a socket on this instance, after their `welcome`. In memory, so it only knows this
 * instance; phase 10 routes commands to the instance that holds the socket.
 */
class ConnectedDrivers : DriverLink {
    private val log = LoggerFactory.getLogger(ConnectedDrivers::class.java)
    private val sessions = ConcurrentHashMap<UserId, MutableSet<WsSession>>()
    private val connectListeners = CopyOnWriteArrayList<suspend (UserId) -> Unit>()
    private val ackListeners = CopyOnWriteArrayList<suspend (UserId, UUID, Boolean) -> Unit>()

    @Suppress("TooGenericExceptionCaught") // a socket that just closed must not stop the others
    override suspend fun send(
        driver: UserId,
        command: Command,
    ): Boolean =
        sessions[driver].orEmpty().toList().count { session ->
            try {
                session.send(command)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("Command {} not sent to a closing socket: {}", command.commandId, e.toString())
                false
            }
        } > 0

    override fun onConnected(listener: suspend (UserId) -> Unit) {
        connectListeners += listener
    }

    override fun onCommandAck(listener: suspend (UserId, UUID, Boolean) -> Unit) {
        ackListeners += listener
    }

    /** After `welcome`: registers the socket, then runs the listeners. A listener that fails is logged. */
    @Suppress("TooGenericExceptionCaught") // a failed delivery must not close the driver's socket
    internal suspend fun connected(
        driver: UserId,
        session: WsSession,
    ) {
        sessions.computeIfAbsent(driver) { ConcurrentHashMap.newKeySet() }.add(session)
        connectListeners.forEach {
            try {
                it(driver)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Delivering pending commands to driver {} failed; they wait for the next connect", driver.value, e)
            }
        }
    }

    internal fun disconnected(
        driver: UserId,
        session: WsSession,
    ) {
        sessions[driver]?.remove(session)
    }

    internal suspend fun acked(
        driver: UserId,
        commandId: UUID,
        applied: Boolean,
    ) = ackListeners.forEach { it(driver, commandId, applied) }
}
