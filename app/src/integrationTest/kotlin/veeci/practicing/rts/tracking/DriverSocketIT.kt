package veeci.practicing.rts.tracking

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.LimitsDto
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.RejectionDto
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedFixBatchRepository
import veeci.practicing.rts.tracking.application.port.out.FixBatchRepository
import veeci.practicing.rts.tracking.application.port.out.InsertResult
import veeci.practicing.rts.tracking.application.port.out.NewBatch
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The driver endpoint end to end: a real WebSocket client against the real application and Postgres. */
@Testcontainers(disabledWithoutDocker = true)
class DriverSocketIT {
    private val sessionId = UUID.randomUUID().toString()
    private val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

    @Test
    fun `TC-2-WS-01 hello is answered with welcome, seq 1 and the server's limits`() =
        testApp {
            driver {
                sendMessage(hello())

                val welcome = receiveMessage().shouldBeInstanceOf<Welcome>()
                welcome.sessionId shouldBe sessionId
                welcome.resumeFromSeq shouldBe 1
                welcome.limits shouldBe LimitsDto(maxFixesPerBatch = 100, maxFrameBytes = 65_536, maxMessagesPerSecond = 20)
            }
        }

    @Test
    fun `TC-2-WS-02 no hello within the handshake timeout closes the socket with 4401`() =
        testApp {
            driver {
                closeReason.await()?.code shouldBe WsCloseCodes.HANDSHAKE_TIMEOUT
            }
        }

    @Test
    fun `after a valid hello the handshake timeout no longer applies`() =
        testApp {
            driver {
                handshake()

                delay(600.milliseconds) // twice the test handshake timeout
                sendMessage(batch(1))

                receiveMessage().shouldBeInstanceOf<Ack>().seq shouldBe 1
            }
        }

    @Test
    fun `TC-2-WS-03 a batch before hello closes the socket with 4401`() =
        testApp {
            driver {
                sendMessage(batch(1))

                closeReason.await()?.code shouldBe WsCloseCodes.HANDSHAKE_TIMEOUT
            }
        }

    @Test
    fun `TC-2-WS-04 an unsupported protocol version closes the socket with 4400`() =
        testApp {
            driver {
                sendMessage(hello().copy(protocolVersion = 2))

                closeReason.await()?.code shouldBe WsCloseCodes.UNSUPPORTED_VERSION
            }
        }

    @Test
    fun `TC-2-WS-05 a batch is acked with its counts, and a resend gets the same ack`() =
        testApp {
            driver {
                handshake()
                val batch = FixBatch(1, listOf(fix(0), fix(1), fix(2).copy(accuracyM = 80.0)))

                sendMessage(batch)
                val ack = receiveMessage()
                sendMessage(batch)

                ack shouldBe Ack(1, accepted = 2, rejected = listOf(RejectionDto(2, "POOR_ACCURACY")))
                receiveMessage() shouldBe ack
            }
        }

    @Test
    fun `a reconnect resumes after the highest stored seq`() =
        testApp {
            driver {
                handshake()
                for (seq in 1L..2L) {
                    sendMessage(batch(seq))
                    receiveMessage().shouldBeInstanceOf<Ack>()
                }
            }

            driver {
                sendMessage(hello().copy(lastAckedSeq = 1))

                receiveMessage().shouldBeInstanceOf<Welcome>().resumeFromSeq shouldBe 3
            }
        }

    @Test
    fun `TC-2-WS-06 malformed JSON gets MALFORMED_MESSAGE and the next batch is still acked`() =
        testApp {
            driver {
                handshake()

                send(Frame.Text("""{"type":"fix_batch","""))
                receiveError().code shouldBe WsErrorCodes.MALFORMED_MESSAGE

                sendMessage(batch(1))
                receiveMessage().shouldBeInstanceOf<Ack>().seq shouldBe 1
            }
        }

    @Test
    fun `TC-2-WS-07 an unknown message type gets UNKNOWN_MESSAGE and the session stays open`() =
        testApp {
            driver {
                handshake()

                send(Frame.Text("""{"type":"device_status","battery":80}"""))
                receiveError().code shouldBe WsErrorCodes.UNKNOWN_MESSAGE

                sendMessage(batch(1))
                receiveMessage().shouldBeInstanceOf<Ack>().seq shouldBe 1
            }
        }

    @Test
    fun `TC-2-WS-08 an oversized batch gets BATCH_TOO_LARGE for its seq and no ack`() =
        testApp {
            driver {
                handshake()

                sendMessage(FixBatch(1, List(101) { fix(it) }))
                val error = receiveError()
                sendMessage(batch(2))

                error.code shouldBe "BATCH_TOO_LARGE"
                error.correlatesTo shouldBe 1
                receiveMessage().shouldBeInstanceOf<Ack>().seq shouldBe 2 // nothing was sent for seq 1
            }
        }

    @Test
    fun `TC-2-WS-09 messages above the rate limit get RATE_LIMITED`() =
        testApp(env = mapOf("WS_MESSAGESPERSECOND" to "2")) {
            driver {
                sendMessage(hello())
                sendMessage(batch(1))
                sendMessage(batch(2))

                receiveMessage().shouldBeInstanceOf<Welcome>()
                receiveMessage().shouldBeInstanceOf<Ack>()
                receiveError().code shouldBe WsErrorCodes.RATE_LIMITED
            }
        }

    @Test
    fun `TC-2-WS-10 the ack is sent only after the batch is committed`() {
        val gate = CompletableDeferred<Unit>()
        val gated =
            module {
                single<FixBatchRepository> { GatedFixBatchRepository(gate, ExposedFixBatchRepository()) }
            }
        testApp(overrides = listOf(gated)) {
            driver {
                handshake()
                sendMessage(batch(1))

                withTimeoutOrNull(500.milliseconds) { incoming.receive() }.shouldBeNull()

                gate.complete(Unit)
                receiveMessage().shouldBeInstanceOf<Ack>().seq shouldBe 1
            }
        }
    }

    @Test
    fun `TC-2-WS-11 two drivers at the same time each get their own acks`() =
        testApp {
            val client = createClient { install(WebSockets) }
            coroutineScope {
                repeat(2) {
                    launch {
                        val session = UUID.randomUUID().toString()
                        client.webSocket(DRIVER) {
                            sendMessage(hello().copy(sessionId = session, deviceId = "phone-$it"))
                            receiveMessage().shouldBeInstanceOf<Welcome>().sessionId shouldBe session
                            val acks =
                                (1L..3L).map { seq ->
                                    sendMessage(batch(seq))
                                    receiveMessage().shouldBeInstanceOf<Ack>().seq
                                }
                            acks shouldBe listOf(1L, 2L, 3L)
                        }
                    }
                }
            }
        }

    @Test
    fun `a hello with an invalid session id gets INVALID_MESSAGE and the next valid hello is welcomed`() =
        testApp {
            driver {
                sendMessage(hello().copy(sessionId = "not-a-uuid"))
                receiveError().code shouldBe WsErrorCodes.INVALID_MESSAGE

                sendMessage(hello())
                receiveMessage().shouldBeInstanceOf<Welcome>()
            }
        }

    @Test
    fun `a second hello on the same socket gets INVALID_MESSAGE`() =
        testApp {
            driver {
                handshake()

                sendMessage(hello())

                receiveError().code shouldBe WsErrorCodes.INVALID_MESSAGE
            }
        }

    @Test
    fun `a session started by another device is refused with SESSION_DEVICE_MISMATCH`() =
        testApp {
            driver { handshake() }

            driver {
                sendMessage(hello().copy(deviceId = "someone-else"))

                receiveError().code shouldBe "SESSION_DEVICE_MISMATCH"
            }
        }

    // --- helpers -------------------------------------------------------------------------------------------

    private suspend fun ApplicationTestBuilder.driver(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        wsClient().webSocket(DRIVER) { block() }

    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private fun hello() = Hello(protocolVersion = 1, deviceId = "phone-1", sessionId = sessionId, lastAckedSeq = null, sdkVersion = "0.9.0")

    /** A fix [i] seconds into the last minute, 11 m further north than fix i-1: a believable 11 m/s. */
    private fun fix(i: Int) = FixDto(lat = 10.7725 + i * 0.0001, lng = 106.698, accuracyM = 5.0, recordedAt = now - 60.seconds + i.seconds)

    private fun batch(seq: Long) = FixBatch(seq, listOf(fix((seq * 3).toInt()), fix((seq * 3 + 1).toInt())))

    private suspend fun DefaultClientWebSocketSession.handshake() {
        sendMessage(hello())
        receiveMessage().shouldBeInstanceOf<Welcome>()
    }

    private suspend fun DefaultClientWebSocketSession.sendMessage(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): ServerMessage =
        ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())

    private suspend fun DefaultClientWebSocketSession.receiveError(): ErrorMessage = receiveMessage().shouldBeInstanceOf<ErrorMessage>()

    /** Waits for [gate] before every insert, so a test can check what the client saw before the commit. */
    private class GatedFixBatchRepository(
        private val gate: CompletableDeferred<Unit>,
        private val delegate: FixBatchRepository,
    ) : FixBatchRepository by delegate {
        override suspend fun insert(batch: NewBatch): InsertResult {
            gate.await()
            return delegate.insert(batch)
        }
    }

    private companion object {
        const val DRIVER = "/ws/v1/driver"
    }
}
