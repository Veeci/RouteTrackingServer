package veeci.practicing.rts.platform.ws

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.SerializationStrategy
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import java.util.concurrent.ConcurrentHashMap

/** One open WebSocket connection, as message handlers see it. */
class WsSession internal constructor(
    val id: String,
    val endpoint: String,
    private val socket: DefaultWebSocketServerSession,
) {
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
}

/** Every open session: shutdown says goodbye to each (step 10), and /metrics shows how many there are. */
class WsSessionRegistry(
    metrics: MeterRegistry,
) {
    private val sessions = ConcurrentHashMap.newKeySet<WsSession>()

    init {
        metrics.gauge("ws.sessions.open", sessions) { it.size.toDouble() }
    }

    val size: Int get() = sessions.size

    internal fun add(session: WsSession) = sessions.add(session)

    internal fun remove(session: WsSession) = sessions.remove(session)

    /** Closes every open session with [reason]; a session that is already gone is skipped. */
    suspend fun closeAll(reason: CloseReason) {
        sessions.toList().forEach { runCatching { it.close(reason) } }
    }
}
