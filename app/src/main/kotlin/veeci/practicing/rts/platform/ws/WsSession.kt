package veeci.practicing.rts.platform.ws

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationStrategy
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** One open WebSocket connection, as message handlers see it. */
class WsSession internal constructor(
    val id: String,
    val endpoint: String,
    private val socket: DefaultWebSocketServerSession,
    private val handler: Job,
) {
    /** Completed by the runner when the handler has finished, after the session left the registry. */
    internal val finished: CompletableJob = Job()

    suspend fun send(message: ServerMessage) = send(ServerMessage.serializer(), message)

    /** For endpoints whose replies are not [ServerMessage]s (the diagnostic echo). */
    suspend fun <T> send(
        serializer: SerializationStrategy<T>,
        message: T,
    ) = socket.send(Frame.Text(ProtocolJson.encodeToString(serializer, message)))

    suspend fun sendError(
        code: String,
        message: String,
        correlatesTo: Long? = null,
    ) = send(ErrorMessage(code, message, correlatesTo))

    internal suspend fun close(reason: CloseReason) = socket.close(reason)

    /** Queues a close frame without waiting; Ktor sends it and finishes the closing handshake. */
    internal fun goAway(reason: CloseReason): Boolean = socket.outgoing.trySend(Frame.Close(reason)).isSuccess

    /**
     * Waits for both coroutines of a session: the handler (it runs in the call's coroutine) and the connection
     * (work an endpoint launches in the socket, such as a subscription, is its child). On Netty the connection
     * ends first, once the close handshake is done, while the handler may still be working.
     */
    internal suspend fun awaitEnd() {
        finished.join()
        socket.coroutineContext.job.join()
    }

    internal fun cancel() {
        handler.cancel()
        socket.coroutineContext.job.cancel()
    }
}

/** Every open session: shutdown says goodbye to each (step 10), and /metrics shows how many there are. */
class WsSessionRegistry(
    private val metrics: MeterRegistry,
) {
    private val sessions = ConcurrentHashMap.newKeySet<WsSession>()

    init {
        metrics.gauge("ws.sessions.open", sessions) { it.size.toDouble() }
    }

    val size: Int get() = sessions.size

    private val endpointGauges = ConcurrentHashMap.newKeySet<String>()

    internal fun add(session: WsSession): Boolean {
        // rts_driver_sessions_active, rts_guest_sessions_active: one gauge per endpoint, made on its first session.
        if (endpointGauges.add(session.endpoint)) {
            metrics.gauge(
                "rts.${session.endpoint}.sessions.active",
                sessions,
            ) { all -> all.count { it.endpoint == session.endpoint }.toDouble() }
        }
        return sessions.add(session)
    }

    internal fun remove(session: WsSession) = sessions.remove(session)

    /**
     * Asks every open session to close with [reason], waits up to [timeout] for them to end, cancels the ones
     * still open after that, and returns how many were asked. When it returns, no session is running.
     */
    suspend fun closeAll(
        reason: CloseReason,
        timeout: Duration,
    ): Int {
        val open = sessions.toList()
        val asked = open.count { it.goAway(reason) }
        if (withTimeoutOrNull(timeout) { open.forEach { it.awaitEnd() } } == null) {
            open.forEach { it.cancel() }
            open.forEach { it.awaitEnd() }
        }
        return asked
    }
}
