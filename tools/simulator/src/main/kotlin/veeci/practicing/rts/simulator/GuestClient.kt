package veeci.practicing.rts.simulator

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.GuestClientMessage
import veeci.practicing.rts.protocol.GuestServerMessage
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Subscribe

/** A guest app that watches one trip live. The simulator CLI and the end-to-end tests use it. */
class GuestClient(
    private val http: HttpClient,
    private val url: String,
    private val token: String,
) {
    /**
     * Subscribes to [tripId] and passes every message to [onMessage] until the server closes the socket (the trip
     * ended) or the caller cancels. Returns how the socket was closed. An `error` from the server ends the watch with
     * an exception.
     */
    suspend fun watch(
        tripId: String,
        onMessage: (GuestServerMessage) -> Unit,
    ): CloseReason? {
        var reason: CloseReason? = null
        http.webSocket(url, request = { bearerAuth(token) }) {
            send(Frame.Text(ProtocolJson.encodeToString(GuestClientMessage.serializer(), Subscribe(tripId))))
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val type =
                    ProtocolJson
                        .parseToJsonElement(text)
                        .jsonObject["type"]
                        ?.jsonPrimitive
                        ?.content
                if (type == "error") {
                    val error = ProtocolJson.decodeFromString(ServerMessage.serializer(), text) as ErrorMessage
                    error("The server refused: ${error.code} ${error.message}")
                }
                onMessage(ProtocolJson.decodeFromString(GuestServerMessage.serializer(), text))
            }
            reason = closeReason.await()
        }
        return reason
    }
}
