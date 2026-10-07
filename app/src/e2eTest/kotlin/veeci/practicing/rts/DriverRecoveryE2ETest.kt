package veeci.practicing.rts

import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.config.AppConfig
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.PROTOCOL_VERSION
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.simulator.DriverClient
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.testApp
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Each test breaks one part of the system while a driver sends a route: the phone, the server, the database.
 * The result must always be the same: every batch stored exactly once, nothing lost, the right running total.
 */
@Testcontainers(disabledWithoutDocker = true)
class DriverRecoveryE2ETest {
    private val fixes = routeFixes("routes/city_loop.gpx")
    private val batches = fixes.chunked(10)

    @Test
    fun `TC-2-E2E-02 a batch whose ack was lost is stored once after the reconnect`() =
        testApp {
            val http = createClient { install(WebSockets) }
            val welcomes = mutableListOf<Welcome>()
            val client = DriverClient(http, DRIVER_PATH, "e2e-killed", onWelcome = { welcomes += it })
            batches.forEach(client::enqueue)

            client.connectAndSend(maxBatches = 4)
            // The phone sends batch 5 from its outbox and dies before the ack arrives.
            http.webSocket(DRIVER_PATH) {
                sendMessage(Hello(PROTOCOL_VERSION, client.deviceId, client.sessionId, lastAckedSeq = 4, sdkVersion = "e2e"))
                incoming.receive() // welcome
                sendMessage(FixBatch(seq = 5, fixes = batches[4]))
            }
            client.connectAndSend()

            // 6: the server stored batch 5 before the connection closed, so only the ack was lost.
            // 5: the server had not stored it yet, so the client sends it again.
            welcomes.last().resumeFromSeq shouldBeIn listOf(5L, 6L)
            client.pending shouldBe 0
            StoredTrack(client.sessionId).shouldHoldTheWholeRoute()
        }

    @Test
    fun `TC-2-E2E-04 a server restart in the middle of a route loses nothing`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val config = TestConfig.load(TestDatabase.env())
            val servers = CopyOnWriteArrayList(listOf(startServer(port, config)))
            val stopped = CompletableDeferred<Unit>()
            try {
                HttpClient(CIO) { install(WebSockets) }.use { http ->
                    val client =
                        DriverClient(
                            http,
                            "ws://localhost:$port$DRIVER_PATH",
                            "e2e-restart",
                            ackTimeout = 2.seconds,
                            onAck = { ack ->
                                // The server goes down between two batches, while the connection is open.
                                if (ack.seq == 6L) {
                                    servers.last().stop(0, 0)
                                    stopped.complete(Unit)
                                }
                            },
                        )
                    batches.forEach(client::enqueue)
                    val restart =
                        launch {
                            stopped.await()
                            delay(1.seconds) // the client finds no server for a while and keeps trying
                            servers += startServer(port, config)
                        }

                    client.sendAll(retryDelay = 100.milliseconds, maxFailures = 10) shouldBe true
                    restart.join()

                    client.connections shouldBeGreaterThan 1
                    StoredTrack(client.sessionId).shouldHoldTheWholeRoute()
                }
            } finally {
                servers.forEach { it.stop(0, 0) }
            }
        }

    @Test
    fun `TC-2-RES-01 batches sent while the database is paused are stored once after it is back`() {
        TestDatabase.newContainer().use { postgres ->
            postgres.start()
            val database = TestDatabase.envFor(postgres)
            val paused = AtomicBoolean(false)
            try {
                testApp(env = database) {
                    val pausedNow = CompletableDeferred<Unit>()
                    val acksWhilePaused = AtomicInteger()
                    val client =
                        DriverClient(
                            createClient { install(WebSockets) },
                            DRIVER_PATH,
                            "e2e-db-pause",
                            ackTimeout = 1.seconds,
                            onAck = { ack ->
                                if (paused.get()) acksWhilePaused.incrementAndGet()
                                if (ack.seq == 5L) {
                                    postgres.pause()
                                    paused.set(true)
                                    pausedNow.complete(Unit)
                                }
                            },
                        )
                    batches.forEach(client::enqueue)

                    coroutineScope {
                        val sending = async { client.sendAll(retryDelay = 200.milliseconds, maxFailures = 10) }
                        withTimeout(30.seconds) { pausedNow.await() }
                        delay(5.seconds)
                        paused.set(false)
                        postgres.unpause()
                        sending.await() shouldBe true
                    }

                    // An ack is sent only after the commit, and nothing commits while the database is paused.
                    acksWhilePaused.get() shouldBe 0
                    client.connections shouldBeGreaterThan 1 // the client gave up waiting and connected again
                    StoredTrack(client.sessionId, database).shouldHoldTheWholeRoute()
                }
            } finally {
                if (paused.get()) postgres.unpause()
            }
        }
    }

    /** Every batch once, every fix once, and a running total that counts no batch twice. */
    private fun StoredTrack.shouldHoldTheWholeRoute() {
        seqs() shouldBe (1L..batches.size.toLong()).toList()
        fixCount() shouldBe fixes.size.toLong()
        lastCumulativeM() shouldBe (fixes.lengthM() plusOrMinus 0.001)
    }

    private fun startServer(
        port: Int,
        config: AppConfig,
    ): EmbeddedServer<*, *> = embeddedServer(Netty, configure = { connector { this.port = port } }) { module(config) }.start()

    private suspend fun WebSocketSession.sendMessage(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    private fun GenericContainer<*>.pause() {
        dockerClient.pauseContainerCmd(containerId).exec()
    }

    private fun GenericContainer<*>.unpause() {
        dockerClient.unpauseContainerCmd(containerId).exec()
    }
}
