package veeci.practicing.rts.fleet

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.fleet.FleetFixtures.driver
import veeci.practicing.rts.fleet.FleetFixtures.seed
import veeci.practicing.rts.fleet.application.port.out.FleetRepository
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.FleetClientMessage
import veeci.practicing.rts.protocol.FleetCluster
import veeci.practicing.rts.protocol.FleetDelta
import veeci.practicing.rts.protocol.FleetFilters
import veeci.practicing.rts.protocol.FleetServerMessage
import veeci.practicing.rts.protocol.FleetSnapshot
import veeci.practicing.rts.protocol.FleetSubscribe
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.WS_TICKETS
import veeci.practicing.rts.testing.plantTicket
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.ticket
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The admin fleet socket end to end (phase 11c): tickets over REST, then snapshots and deltas read from
 * `fleet_drivers`, with a 100 ms tick (test.conf). Each test watches its own random 0.01° area, so rows of other
 * tests in the shared database never fall inside it.
 */
@Testcontainers(disabledWithoutDocker = true)
class FleetSocketIT {
    private val lat0 = Random.nextDouble(-60.0, 60.0)
    private val lng0 = Random.nextDouble(-170.0, 170.0)
    private val area = listOf(lng0, lat0, lng0 + SIDE, lat0 + SIDE)

    @Test
    fun `TC-11-FL-01 the snapshot holds only the drivers inside the area that match the filters, edges included`() =
        socketApp {
            val inside = inArea(0.5, 0.5)
            val onEdge = inArea(1.0, 0.0)
            val offline = inArea(0.3, 0.3, ago = 5.minutes)
            val outside = inArea(1.5, 0.5)
            application.seed(inside, onEdge, offline, outside)

            fleet {
                subscribe(online = true)

                val snapshot = receiveFleet().shouldBeInstanceOf<FleetSnapshot>()
                snapshot.drivers.map { it.driverId } shouldContainExactlyInAnyOrder listOf(inside, onEdge).map { it.driverId.toString() }
                snapshot.drivers.first { it.driverId == inside.driverId.toString() }.online shouldBe true

                subscribe()
                receiveFleet().shouldBeInstanceOf<FleetSnapshot>().drivers.size shouldBe 3
            }
        }

    @Test
    fun `TC-11-FL-02 a move inside the area is one upsert, leaving the area is one remove`() =
        socketApp {
            val driver = inArea(0.5, 0.5)
            application.seed(driver)

            fleet {
                subscribe()
                receiveFleet().shouldBeInstanceOf<FleetSnapshot>().drivers.size shouldBe 1

                application.write(driver.at(0.6, 0.6))
                val moved = nextChange()
                moved.upserts.single().lat shouldBe lat0 + 0.6 * SIDE
                moved.removes.shouldBeEmpty()

                application.write(driver.at(2.0, 0.6))
                val left = nextChange()
                left.upserts.shouldBeEmpty()
                left.removes shouldContainExactly listOf(driver.driverId.toString())
            }
        }

    @Test
    fun `a driver who goes silent leaves an online-only map although no row is written`() =
        socketApp {
            // Stale after 60 s: this driver crosses the bound about half a second from now.
            val fading = inArea(0.5, 0.5, ago = 59.5.seconds)
            application.seed(fading)

            fleet {
                subscribe(online = true)
                receiveFleet().shouldBeInstanceOf<FleetSnapshot>().drivers.size shouldBe 1

                nextChange().removes shouldContainExactly listOf(fading.driverId.toString())
            }
        }

    @Test
    fun `TC-11-FL-04 a new subscribe replaces the area, and the old area's changes stop`() =
        socketApp {
            val first = inArea(0.5, 0.5)
            val second = inArea(0.5, 3.5)
            application.seed(first, second)

            fleet {
                subscribe()
                receiveFleet()
                    .shouldBeInstanceOf<FleetSnapshot>()
                    .drivers
                    .single()
                    .driverId shouldBe first.driverId.toString()

                subscribe(bbox = listOf(lng0 + 3 * SIDE, lat0, lng0 + 4 * SIDE, lat0 + SIDE))
                receiveFleet()
                    .shouldBeInstanceOf<FleetSnapshot>()
                    .drivers
                    .single()
                    .driverId shouldBe second.driverId.toString()

                application.write(first.at(0.7, 0.7))
                application.write(second.at(0.7, 3.7))
                val change = nextChange()
                change.upserts.map { it.driverId } shouldContainExactly listOf(second.driverId.toString())
            }
        }

    @Test
    fun `TC-11-FL-05 an area with more drivers than the limit gets clusters, and markers again when it empties`() =
        socketApp(env = mapOf("FLEET_MAXMARKERS" to "2")) {
            val drivers = listOf(inArea(0.1, 0.1), inArea(0.2, 0.2), inArea(0.9, 0.9))
            application.seed(drivers)

            fleet {
                subscribe()
                val cluster = receiveFleet().shouldBeInstanceOf<FleetCluster>()
                cluster.total shouldBe 3
                cluster.cells.sumOf { it.count } shouldBe 3

                application.write(drivers.last().at(5.0, 5.0))
                val snapshot = receiveUntil<FleetSnapshot>()
                snapshot.drivers.map { it.driverId } shouldContainExactlyInAnyOrder drivers.take(2).map { it.driverId.toString() }
            }
        }

    @Test
    fun `an invalid area is an error and the socket stays open`() =
        socketApp {
            fleet {
                subscribe(bbox = listOf(lng0 + 1, lat0, lng0, lat0 + 1))
                receiveFleet().shouldBeInstanceOf<ErrorMessage>().code shouldBe WsErrorCodes.INVALID_MESSAGE

                subscribe()
                receiveFleet().shouldBeInstanceOf<FleetSnapshot>()
            }
        }

    @Test
    fun `TC-11-FL-06 a ticket of a GUEST or DRIVER closes the socket with 4403`() =
        socketApp {
            listOf("GUEST", "DRIVER").forEach { role ->
                val ticket = application.plantTicket(role)
                wsClient().webSocket("$FLEET?ticket=$ticket") {
                    closeReason.await()?.code shouldBe WsCloseCodes.FORBIDDEN_ROLE
                }
            }
        }

    @Test
    fun `TC-11-FL-06 only a ticket opens the socket, not an access token in a header or the URL`() =
        socketApp {
            refused { webSocket(FLEET, request = { bearerAuth(TestTokens.admin()) }) {} }
            refused { webSocket("$FLEET?token=${TestTokens.admin()}") {} }
            refused { webSocket("$FLEET?ticket=not-a-ticket") {} }
        }

    @Test
    fun `TC-11-SEC-03 a ticket works once, and not after it expired`() =
        socketApp {
            val ticket = ticket()
            wsClient().webSocket("$FLEET?ticket=$ticket") {
                subscribe()
                receiveFleet().shouldBeInstanceOf<FleetSnapshot>()
            }
            refused { webSocket("$FLEET?ticket=$ticket") {} }

            val expired = ticket()
            application.sql(
                "UPDATE ws_tickets SET expires_at = now() - interval '1 second' WHERE hash = sha256(convert_to('$expired', 'UTF8'))",
            )
            refused { webSocket("$FLEET?ticket=$expired") {} }
        }

    @Test
    fun `GUEST and DRIVER get no ticket`() =
        socketApp {
            listOf(TestTokens.guest(), TestTokens.driver()).forEach { token ->
                val response = client.post(WS_TICKETS) { bearerAuth(token) }
                response.status shouldBe HttpStatusCode.Forbidden
                response.shouldMatchContract()
                response.bodyAsText() shouldContain "TICKET_FORBIDDEN"
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** A driver at a fraction of the area: (0, 0) is its south-west corner, (1, 1) the north-east one. */
    private fun inArea(
        north: Double,
        east: Double,
        ago: kotlin.time.Duration = 5.seconds,
    ): FleetDriver = driver(lat = lat0 + north * SIDE, lng = lng0 + east * SIDE, ago = ago, id = UUID.randomUUID())

    private fun FleetDriver.at(
        north: Double,
        east: Double,
    ): FleetDriver {
        val now = Clock.System.now()
        return copy(position = position!!.copy(lat = lat0 + north * SIDE, lng = lng0 + east * SIDE, at = now))
    }

    /** Writes [driver] as the projection would: a new row version with the server time. */
    private suspend fun io.ktor.server.application.Application.write(driver: FleetDriver) {
        get<TransactionRunner>().inTransaction { get<FleetRepository>().save(driver, Clock.System.now()) }
    }

    private suspend fun io.ktor.server.application.Application.sql(statement: String) {
        get<TransactionRunner>().inTransaction { TransactionManager.current().exec(statement) }
    }

    private fun SocketTestApp.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun SocketTestApp.fleet(block: suspend DefaultClientWebSocketSession.() -> Unit) {
        val ticket = ticket()
        wsClient().webSocket("$FLEET?ticket=$ticket") { withTimeout(10.seconds) { block() } }
    }

    /** The upgrade is answered 401, so the client never gets a socket. */
    private suspend fun SocketTestApp.refused(connect: suspend HttpClient.() -> Unit) {
        shouldThrowAny { wsClient().connect() }.message shouldContain "401"
    }

    private suspend fun DefaultClientWebSocketSession.subscribe(
        bbox: List<Double> = area,
        online: Boolean? = null,
    ) = send(Frame.Text(ProtocolJson.encodeToString(FleetClientMessage.serializer(), FleetSubscribe(bbox, FleetFilters(online)))))

    private suspend fun DefaultClientWebSocketSession.receiveFleet(): Any {
        val text = (incoming.receive() as Frame.Text).readText()
        val type =
            ProtocolJson
                .parseToJsonElement(text)
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content
        return if (type == "error") {
            ProtocolJson.decodeFromString(ServerMessage.serializer(), text)
        } else {
            ProtocolJson.decodeFromString(FleetServerMessage.serializer(), text)
        }
    }

    private suspend inline fun <reified T> DefaultClientWebSocketSession.receiveUntil(): T {
        while (true) {
            val message = receiveFleet()
            if (message is T) return message
        }
    }

    /** The next delta that carries a change; a delta comes every tick, most of them empty. */
    private suspend fun DefaultClientWebSocketSession.nextChange(): FleetDelta {
        while (true) {
            val delta = receiveFleet().shouldBeInstanceOf<FleetDelta>()
            if (delta.upserts.isNotEmpty() || delta.removes.isNotEmpty()) return delta
        }
    }

    private companion object {
        const val FLEET = "/ws/v1/admin/fleet"
        const val SIDE = 0.01
    }
}
