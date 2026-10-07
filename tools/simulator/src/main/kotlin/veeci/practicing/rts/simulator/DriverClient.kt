package veeci.practicing.rts.simulator

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.PROTOCOL_VERSION
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Welcome
import java.util.UUID
import kotlin.time.Duration

/**
 * A driver device that follows the protocol's client rules. The simulator CLI and the end-to-end tests use it,
 * and it is the reference behaviour for the SDK's transport module:
 * - a batch gets its seq when it enters the outbox, and its content never changes after that;
 * - a batch leaves the outbox only when its ack arrives, or when `welcome` says the server already has it;
 * - after a reconnect, every batch still in the outbox is sent again with the same seq.
 */
class DriverClient(
    private val http: HttpClient,
    private val url: String,
    val deviceId: String,
    val sessionId: String = UUID.randomUUID().toString(),
    private val onAck: (Ack) -> Unit = {},
) {
    private val outbox = ArrayDeque<FixBatch>()
    private var nextSeq = 1L

    /** The highest seq the server has confirmed; sent in `hello`. */
    var lastAckedSeq: Long? = null
        private set

    /** How many batches wait for their ack. */
    val pending: Int get() = outbox.size

    /** Numbers [fixes] as the next batch and keeps it until the server has it. */
    fun enqueue(fixes: List<FixDto>) {
        outbox.addLast(FixBatch(nextSeq++, fixes))
    }

    /**
     * Opens one connection, says hello, and sends the waiting batches one at a time, each after the previous
     * one's ack, with [pace] before each. Returns when the outbox is empty or after [maxBatches] acks, which lets
     * the caller disconnect on purpose. A failed connection throws; the batches stay in the outbox.
     */
    suspend fun connectAndSend(
        pace: Duration = Duration.ZERO,
        maxBatches: Int = Int.MAX_VALUE,
    ) {
        http.webSocket(url) {
            sendMessage(Hello(PROTOCOL_VERSION, deviceId, sessionId, lastAckedSeq, SDK_VERSION))
            val welcome = receiveMessage() as? Welcome ?: error("The server did not answer hello with welcome")
            // A batch below resumeFromSeq is stored already: its ack was lost, not the batch.
            while (outbox.firstOrNull()?.let { it.seq < welcome.resumeFromSeq } == true) {
                lastAckedSeq = outbox.removeFirst().seq
            }
            var acked = 0
            while (outbox.isNotEmpty() && acked < maxBatches) {
                delay(pace)
                val batch = outbox.first()
                sendMessage(batch)
                when (val reply = receiveMessage()) {
                    is Ack -> {
                        check(reply.seq == batch.seq) { "Ack for seq ${reply.seq} while waiting for ${batch.seq}" }
                        outbox.removeFirst()
                        lastAckedSeq = reply.seq
                        acked++
                        onAck(reply)
                    }

                    is ErrorMessage -> {
                        error("The server refused batch ${batch.seq}: ${reply.code} ${reply.message}")
                    }

                    is Welcome -> {
                        error("Unexpected welcome")
                    }
                }
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendMessage(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): ServerMessage =
        ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())

    private companion object {
        const val SDK_VERSION = "simulator"
    }
}
