package veeci.practicing.rts

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.protocol.CommandAck
import veeci.practicing.rts.protocol.DriverPosition
import veeci.practicing.rts.protocol.GuestServerMessage
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Subscribed
import veeci.practicing.rts.protocol.TripEvent
import veeci.practicing.rts.protocol.TripProgress
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.simulator.Backoff
import veeci.practicing.rts.simulator.DriverClient
import veeci.practicing.rts.simulator.GuestClient
import veeci.practicing.rts.testing.TEST_DRIVER_PACE
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.TwoInstances
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.testing.twoInstances
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 10b: two instances behind an imaginary load balancer, sharing Postgres and Redis. Each test puts the
 * driver and the guest on different instances, and checks what the database stored, not only what a socket saw.
 */
@Testcontainers(disabledWithoutDocker = true)
class MultiInstanceE2ETest {
    private val driverId = TestTokens.newUser()
    private val driver = TestTokens.driver(driverId)
    private val guest = TestTokens.guest(TestTokens.newUser())
    private val route = routeFixes("routes/straight_2km.gpx")

    /** The guest waits where the route ends, so the driver arrives. */
    private val pickup = route.last().let { GeoPoint(it.lat, it.lng) }
    private val destination = GeoPoint(10.80, 106.70)
    private val http = HttpClient(CIO) { install(WebSockets) }

    @Test
    fun `TC-10-MI-01 a guest on instance B sees the positions, progress and events of a driver on A`() =
        twoInstances {
            val trip = TripApi(a.client).created(guest, pickup, destination)
            TripApi(a.client).act(trip.id, "accept", driver)
            val messages = ConcurrentLinkedQueue<GuestServerMessage>()

            coroutineScope {
                val watching = async { GuestClient(http, "$wsB$GUEST_PATH", guest).watch(trip.id) { messages += it } }
                eventually { messages.filterIsInstance<Subscribed>().shouldNotBeEmpty() }
                DriverClient(
                    http,
                    "$wsA$DRIVER_PATH",
                    "mi-01",
                    token = driver,
                ).apply { route.chunked(10).forEach(::enqueue) }.connectAndSend(pace = TEST_DRIVER_PACE)
                eventually { messages.filterIsInstance<TripEvent>().map { it.status } shouldContain "ARRIVED" }
                TripApi(a.client).moved(trip.id, driver, "start", "complete")
                withTimeout(10.seconds) { watching.await() }
            }

            messages.filterIsInstance<DriverPosition>().shouldNotBeEmpty()
            messages.filterIsInstance<TripProgress>().shouldNotBeEmpty()
            messages.filterIsInstance<TripEvent>().last().status shouldBe "COMPLETED"
        }

    @Test
    fun `TC-10-MI-02 a command an admin issues on A reaches the driver's socket on B once, and is applied`() =
        twoInstances {
            val received = ConcurrentLinkedQueue<Command>()
            coroutineScope {
                val socket =
                    async {
                        http.webSocket("$wsB$DRIVER_PATH", request = { bearerAuth(driver) }) {
                            send(Hello(1, "mi-02", UUID.randomUUID().toString(), sdkVersion = "e2e"))
                            (receive() is Welcome) shouldBe true
                            while (true) {
                                val command = receive() as? Command ?: continue
                                received += command
                                send(CommandAck(command.commandId, CommandAck.APPLIED))
                            }
                        }
                    }
                delay(500) // B subscribes to the driver's channel right after welcome
                a.client
                    .post("/api/v1/admin/drivers/${driverId.value}/commands") {
                        bearerAuth(TestTokens.admin())
                        contentType(ContentType.Application.Json)
                        setBody("""{"kind":"FLUSH_QUEUE"}""")
                    }.status shouldBe HttpStatusCode.Accepted

                eventually { commandStatuses() shouldBe listOf("APPLIED") }
                delay(300) // a second copy would have arrived by now
                received.size shouldBe 1
                socket.cancel()
            }
        }

    @Test
    fun `TC-10-MI-03 a driver who goes silent is reported stale once, though both instances run the stale job`() =
        twoInstances(env = mapOf("EVENTS_STALEAFTER" to "2s", "EVENTS_STALECHECKEVERY" to "200ms")) {
            val trip = TripApi(a.client).created(guest, pickup, destination)
            TripApi(a.client).act(trip.id, "accept", driver)
            DriverClient(
                http,
                "$wsA$DRIVER_PATH",
                "mi-03",
                token = driver,
            ).apply { route.take(10).chunked(10).forEach(::enqueue) }.connectAndSend()

            eventually { moments(trip.id)["DRIVER_STALE"] shouldBe 1 }
            delay(1_000) // five more rounds of the job on each instance
            moments(trip.id)["DRIVER_STALE"] shouldBe 1
        }

    @Test
    fun `TC-10-MI-04 when A stops mid-trip, driver and guest carry on through B with nothing lost and nothing twice`() =
        twoInstances {
            val trip = TripApi(a.client).created(guest, pickup, destination)
            TripApi(a.client).act(trip.id, "accept", driver)
            val messages = ConcurrentLinkedQueue<GuestServerMessage>()
            val client = DriverClient(http, "$wsA$DRIVER_PATH", "mi-04", token = driver).apply { route.chunked(10).forEach(::enqueue) }
            val batches = client.pending

            coroutineScope {
                val watching =
                    async {
                        GuestClient(http, "$wsA$GUEST_PATH", guest).watchUntilEnded(
                            trip.id,
                            onMessage = { messages += it },
                            backoff = Backoff(base = 50.milliseconds, cap = 200.milliseconds),
                            urls = { attempt -> if (attempt == 0) "$wsA$GUEST_PATH" else "$wsB$GUEST_PATH" },
                        )
                    }
                eventually { messages.filterIsInstance<Subscribed>().shouldNotBeEmpty() }
                client.connectAndSend(maxBatches = 5)

                stopA()
                client.url = "$wsB$DRIVER_PATH" // the load balancer sends the reconnect to the instance left
                client.sendAll(retryDelay = 50.milliseconds) shouldBe true
                eventually { messages.filterIsInstance<TripEvent>().map { it.status } shouldContain "ARRIVED" }
                TripApi(b.client).moved(trip.id, driver, "start", "complete")
                withTimeout(15.seconds) { watching.await() }?.code shouldBe 1000.toShort()
            }

            storedSeqs(client.sessionId) shouldBe (1L..batches.toLong()).toList()
            moments(trip.id).filterValues { it > 1 }.keys.shouldBeEmpty()
            messages.filterIsInstance<Subscribed>().size shouldBeGreaterThanOrEqual 2 // it reconnected
            messages.filterIsInstance<TripEvent>().last().status shouldBe "COMPLETED"
        }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.send(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receive(): ServerMessage =
        ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())

    private fun TwoInstances.commandStatuses(): List<String> =
        query("SELECT status FROM device_commands WHERE driver_id = '${driverId.value}'")

    private fun TwoInstances.moments(trip: String): Map<String, Int> =
        query("SELECT kind FROM trip_moments WHERE trip_id = '$trip'").groupingBy { it }.eachCount()

    private fun TwoInstances.storedSeqs(session: String): List<Long> =
        query("SELECT seq FROM fix_batches WHERE session_id = '$session' ORDER BY seq").map { it.toLong() }

    private fun TwoInstances.query(sql: String): List<String> =
        DriverManager.getConnection(dbEnv["DB_URL"], dbEnv["DB_USER"], dbEnv["DB_PASSWORD"]).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
            }
        }

    /** Retries [assertion] for up to 10 s: what one instance did shows up on the other a little later. */
    private suspend fun eventually(assertion: suspend () -> Unit) {
        withTimeout(10.seconds) {
            while (runCatching { assertion() }.isFailure) delay(50.milliseconds)
        }
        assertion()
    }

    private companion object {
        const val GUEST_PATH = "/ws/v1/guest"
    }
}
