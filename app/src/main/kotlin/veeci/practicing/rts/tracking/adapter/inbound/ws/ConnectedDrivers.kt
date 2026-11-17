package veeci.practicing.rts.tracking.adapter.inbound.ws

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.ws.WsSession
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.LocalDriverLink
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which drivers have a socket on this instance, after their `welcome`. In memory, so it only knows this
 * instance; phase 10 routes commands to the instance that holds the socket.
 */
class ConnectedDrivers : LocalDriverLink {
    private val log = LoggerFactory.getLogger(ConnectedDrivers::class.java)
    private val sessions = ConcurrentHashMap<UserId, MutableSet<WsSession>>()
    private val connectListeners = CopyOnWriteArrayList<suspend (UserId) -> Unit>()
    private val ackListeners = CopyOnWriteArrayList<suspend (UserId, UUID, Boolean) -> Unit>()
    private val presenceListeners = CopyOnWriteArrayList<suspend (UserId, Boolean) -> Unit>()

    override fun onPresenceChanged(listener: suspend (driver: UserId, here: Boolean) -> Unit) {
        presenceListeners += listener
    }

    @Suppress("TooGenericExceptionCaught") // a socket that just closed must not stop the others
    override suspend fun send(
        driver: UserId,
        message: ServerMessage,
    ): Boolean =
        sessions[driver].orEmpty().toList().count { session ->
            try {
                session.send(message)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("{} not sent to a closing socket: {}", message::class.simpleName, e.toString())
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
        var first = false
        // compute() is atomic per key, so a socket that closes at the same moment cannot lose this one.
        sessions.compute(driver) { _, sockets ->
            val set = sockets ?: ConcurrentHashMap.newKeySet()
            first = set.isEmpty()
            set.add(session)
            set
        }
        if (first) presenceListeners.forEach { it(driver, true) }
        connectListeners.forEach {
            try {
                it(driver)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("A connect listener failed for driver {}; it runs again on the next connect", driver.value, e)
            }
        }
    }

    internal suspend fun disconnected(
        driver: UserId,
        session: WsSession,
    ) {
        var last = false
        sessions.computeIfPresent(driver) { _, set ->
            set.remove(session)
            last = set.isEmpty()
            if (last) null else set
        }
        if (last) presenceListeners.forEach { it(driver, false) }
    }

    internal suspend fun acked(
        driver: UserId,
        commandId: UUID,
        applied: Boolean,
    ) = ackListeners.forEach { it(driver, commandId, applied) }
}
