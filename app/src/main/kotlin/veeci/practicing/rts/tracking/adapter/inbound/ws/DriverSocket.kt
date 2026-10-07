package veeci.practicing.rts.tracking.adapter.inbound.ws

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.ws.WsSession
import veeci.practicing.rts.platform.ws.WsSessionRunner
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.LimitsDto
import veeci.practicing.rts.protocol.PROTOCOL_VERSION
import veeci.practicing.rts.protocol.RejectionDto
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.tracking.application.TrackingService
import veeci.practicing.rts.tracking.domain.SessionId
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.toKotlinInstant

fun Route.driverSocket(socket: DriverSocket) {
    webSocket("/ws/v1/driver") { socket.serve(this) }
}

/**
 * The driver endpoint: a `hello` first, then numbered fix batches. Each batch is answered with an `ack` only
 * after [TrackingService.ingest] has committed it. Messages of one socket are handled one at a time, in order.
 */
class DriverSocket(
    private val runner: WsSessionRunner,
    private val tracking: TrackingService,
    private val clock: Clock,
    private val limits: LimitsDto,
    private val handshakeTimeout: Duration,
) {
    private val log = LoggerFactory.getLogger(DriverSocket::class.java)

    suspend fun serve(socket: DefaultWebSocketServerSession) {
        var session: SessionId? = null // set by the first valid hello
        val handshake =
            socket.launch {
                delay(handshakeTimeout)
                socket.close(CloseReason(WsCloseCodes.HANDSHAKE_TIMEOUT, "No valid hello within $handshakeTimeout"))
            }
        try {
            runner.run(socket, ENDPOINT, ClientMessage.serializer()) { message ->
                val current = session
                when {
                    message is Hello && current == null -> session = hello(message)?.also { handshake.cancel() }
                    message is Hello -> sendError(WsErrorCodes.INVALID_MESSAGE, "This socket already sent its hello.")
                    message is FixBatch && current != null -> batch(current, message)
                    else -> close(CloseReason(WsCloseCodes.HANDSHAKE_TIMEOUT, "The first message must be hello"))
                }
            }
        } finally {
            handshake.cancel()
        }
    }

    /** Opens or resumes the session and sends `welcome`. Null if the hello was refused. */
    private suspend fun WsSession.hello(hello: Hello): SessionId? {
        val command = DriverMessageMapper.openSession(hello)
        return when {
            hello.protocolVersion != PROTOCOL_VERSION -> {
                close(CloseReason(WsCloseCodes.UNSUPPORTED_VERSION, "Protocol version $PROTOCOL_VERSION is required"))
                null
            }

            command == null -> {
                sendError(WsErrorCodes.INVALID_MESSAGE, "sessionId must be a UUID and deviceId 1 to 128 characters.")
                null
            }

            else -> {
                answer {
                    val opened = tracking.openSession(command)
                    send(Welcome(opened.sessionId.value.toString(), opened.resumeFromSeq, clock.instant().toKotlinInstant(), limits))
                    log.info(
                        "Driver session {} (device {}) resumes from seq {}; the client last saw an ack for {}",
                        opened.sessionId.value,
                        command.deviceId.value,
                        opened.resumeFromSeq,
                        hello.lastAckedSeq,
                    )
                    opened.sessionId
                }
            }
        }
    }

    private suspend fun WsSession.batch(
        session: SessionId,
        batch: FixBatch,
    ) {
        answer(correlatesTo = batch.seq) {
            val result = tracking.ingest(DriverMessageMapper.ingest(session, batch))
            send(Ack(result.seq, result.accepted, result.rejected.map { RejectionDto(it.index, it.reason.name) }))
        }
    }

    /**
     * Runs [block]; a business rule that refuses it becomes an `error` frame and the socket stays open.
     * Any other exception goes to the runner, which closes the socket with 1011 (the client reconnects).
     */
    private suspend fun <T> WsSession.answer(
        correlatesTo: Long? = null,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (e: DomainException) {
            sendError(e.code.name, e.message ?: e.code.name, correlatesTo)
            null
        }

    private companion object {
        const val ENDPOINT = "driver"
    }
}
