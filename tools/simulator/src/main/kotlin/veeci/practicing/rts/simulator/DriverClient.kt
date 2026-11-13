package veeci.practicing.rts.simulator

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.protocol.CommandAck
import veeci.practicing.rts.protocol.DeviceStatus
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
import kotlin.time.Duration.Companion.seconds

/**
 * A driver device that follows the protocol's client rules. The simulator CLI and the end-to-end tests use it,
 * and it is the reference behaviour for the SDK's transport module:
 * - a batch gets its seq when it enters the outbox, and its content never changes after that;
 * - a batch leaves the outbox only when its ack arrives, or when `welcome` says the server already has it;
 * - after a reconnect, every batch still in the outbox is sent again with the same seq;
 * - if the server does not answer within [ackTimeout], the client gives up on the connection and opens a new one.
 */
class DriverClient(
    private val http: HttpClient,
    private val url: String,
    val deviceId: String,
    val sessionId: String = UUID.randomUUID().toString(),
    /** The driver's access token, sent as `Authorization: Bearer` when the socket opens. */
    private val token: String? = null,
    private val ackTimeout: Duration = DEFAULT_ACK_TIMEOUT,
    private val onWelcome: (Welcome) -> Unit = {},
    private val onAck: (Ack) -> Unit = {},
    private val onConnectionFailed: (Exception) -> Unit = {},
) {
    /** What the phone does around each batch besides sending it: heartbeats, silences. */
    var hooks: BatchHooks = BatchHooks.NONE

    private val outbox = ArrayDeque<FixBatch>()
    private var nextSeq = 1L

    /** The highest seq the server has confirmed; sent in `hello`. */
    var lastAckedSeq: Long? = null
        private set

    /** How many batches wait for their ack. */
    val pending: Int get() = outbox.size

    /** How many connections [sendAll] has opened, including the failed ones. */
    var connections = 0
        private set

    /** Numbers [fixes] as the next batch and keeps it until the server has it. */
    fun enqueue(fixes: List<FixDto>) {
        outbox.addLast(FixBatch(nextSeq++, fixes))
    }

    /**
     * Sends until the outbox is empty. After a connection ends or fails, the client connects again, like a phone
     * that lost the network. The wait before the next attempt starts at [retryDelay] and doubles after each failed
     * connection in a row. Returns false if [maxFailures] connections in a row fail; the batches stay in the outbox.
     */
    @Suppress("TooGenericExceptionCaught") // any failure (refused, reset, closed by the server, no answer) means: try again
    suspend fun sendAll(
        pace: Duration = Duration.ZERO,
        batchesPerConnection: Int = Int.MAX_VALUE,
        retryDelay: Duration = 1.seconds,
        maxFailures: Int = DEFAULT_MAX_FAILURES,
    ): Boolean {
        var failures = 0
        var wait = retryDelay
        while (outbox.isNotEmpty() && failures < maxFailures) {
            connections++
            try {
                connectAndSend(pace, batchesPerConnection)
                failures = 0
                wait = retryDelay
            } catch (e: Exception) {
                // A dropped connection can also end in a CancellationException: Ktor cancels the socket's channels.
                // Only a cancelled caller stops the client.
                currentCoroutineContext().ensureActive()
                failures++
                onConnectionFailed(e)
                if (failures < maxFailures) {
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_DELAY)
                }
            }
        }
        return outbox.isEmpty()
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
        http.webSocket(url, request = { token?.let(::bearerAuth) }) {
            sendMessage(Hello(PROTOCOL_VERSION, deviceId, sessionId, lastAckedSeq, SDK_VERSION))
            val welcome = receiveMessage() as? Welcome ?: error("The server did not answer hello with welcome")
            onWelcome(welcome)
            // A batch below resumeFromSeq is stored already: its ack was lost, not the batch.
            while (outbox.firstOrNull()?.let { it.seq < welcome.resumeFromSeq } == true) {
                lastAckedSeq = outbox.removeFirst().seq
            }
            var acked = 0
            while (outbox.isNotEmpty() && acked < maxBatches) {
                val batch = outbox.first()
                delay(pace + hooks.pauseBefore(batch))
                hooks.statusBefore(batch)?.let { sendMessage(it) }
                sendMessage(batch)
                when (val reply = receiveSkippingCommands()) {
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

                    is Command -> {
                        error("receiveSkippingCommands returned a command")
                    }
                }
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendMessage(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    /** The next message that is not a `command`; commands in between are answered with `command_ack`. */
    private suspend fun DefaultClientWebSocketSession.receiveSkippingCommands(): ServerMessage {
        while (true) {
            val message = receiveMessage()
            if (message !is Command) return message
            sendMessage(CommandAck(message.commandId, hooks.answer(message)))
        }
    }

    /** The next message, or an error if none arrives within [ackTimeout] (for example while the server's database hangs). */
    private suspend fun DefaultClientWebSocketSession.receiveMessage(): ServerMessage {
        val frame = withTimeoutOrNull(ackTimeout) { incoming.receive() } ?: error("No answer from the server within $ackTimeout")
        return ProtocolJson.decodeFromString(ServerMessage.serializer(), (frame as Frame.Text).readText())
    }

    private companion object {
        const val SDK_VERSION = "simulator"
        const val DEFAULT_MAX_FAILURES = 5
        val DEFAULT_ACK_TIMEOUT = 10.seconds
        val MAX_RETRY_DELAY = 30.seconds
    }
}

/** What a simulated phone does before sending a batch, besides waiting for its pace. */
interface BatchHooks {
    /** A `device_status` to send right before [batch], when one is due. The server sends no reply to it. */
    fun statusBefore(batch: FixBatch): DeviceStatus?

    /** Extra silence before [batch]: a phone without network for a while. */
    fun pauseBefore(batch: FixBatch): Duration

    /** The answer to a server [command]: applied (the default) or rejected. */
    fun answer(command: Command): String = CommandAck.APPLIED

    companion object {
        /** Only batches, no heartbeats and no silences: the behaviour before phase 4. */
        val NONE =
            object : BatchHooks {
                override fun statusBefore(batch: FixBatch): DeviceStatus? = null

                override fun pauseBefore(batch: FixBatch) = Duration.ZERO
            }
    }
}
