package veeci.practicing.rts.live

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.live.application.api.DriverPositionUpdate
import veeci.practicing.rts.live.application.api.TripProgressUpdate
import veeci.practicing.rts.live.application.port.out.LiveUpdateBus
import veeci.practicing.rts.protocol.DriverPosition
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.GuestClientMessage
import veeci.practicing.rts.protocol.GuestServerMessage
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.RouteUpdated
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Subscribe
import veeci.practicing.rts.protocol.Subscribed
import veeci.practicing.rts.protocol.TripEvent
import veeci.practicing.rts.protocol.TripProgress
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.plantTicket
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.ticket
import veeci.practicing.rts.testing.trip.TripApi
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The staff trip socket (phase 12a, decision D8): tickets as on the fleet socket, the guest's protocol, and any trip
 * for OPERATOR and ADMIN. The live updates are published on the bus directly: the path from a driver's fix to the
 * bus is the guest socket's, tested in GuestSocketIT.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminTripSocketIT {
    private val guest = TestTokens.guest()
    private val driver = TestTokens.driver()

    @Test
    fun `TC-12-SRV-03 an OPERATOR watches another user's trip live until it ends`() =
        socketApp {
            val trip = api().created(guest)
            api().act(trip.id, "accept", driver)
            val tripId = UUID.fromString(trip.id)

            watch(ticket(TestTokens.operator())) {
                subscribe(trip.id)
                receiveTrip().shouldBeInstanceOf<Subscribed>().trip.status shouldBe "ACCEPTED"
                awaitWatcher(tripId)

                bus().publish(DriverPositionUpdate(tripId, GeoPoint(21.03, 105.85), 90.0, 8.0, Clock.System.now()))
                receiveSkippingEvents().shouldBeInstanceOf<DriverPosition>().lat shouldBe 21.03
                bus().publish(TripProgressUpdate(tripId, "PICKUP", 420.0, 60.0, "STRAIGHT_LINE"))
                receiveSkippingEvents().shouldBeInstanceOf<TripProgress>().remainingM shouldBe 420.0

                api().moved(trip.id, driver, "arrive", "start", "complete")
                receiveStatus("COMPLETED").event shouldBe TripEvent.STATUS_CHANGED
                withTimeout(5.seconds) { closeReason.await() }?.code shouldBe CloseReason.Codes.NORMAL.code
            }
        }

    @Test
    fun `TC-12-SRV-03 an ADMIN ticket watches a trip too`() =
        socketApp {
            val trip = api().created(guest)

            watch(ticket(TestTokens.admin())) {
                subscribe(trip.id)
                receiveTrip().shouldBeInstanceOf<Subscribed>().trip.tripId shouldBe trip.id
            }
        }

    @Test
    fun `TC-12-SRV-03 a ticket of a GUEST or DRIVER closes the socket with 4403`() =
        socketApp {
            listOf("GUEST", "DRIVER").forEach { role ->
                wsClient().webSocket("$TRIP?ticket=${application.plantTicket(role)}") {
                    closeReason.await()?.code shouldBe WsCloseCodes.FORBIDDEN_ROLE
                }
            }
        }

    @Test
    fun `TC-12-SRV-03 a used ticket is refused with 401 before the upgrade, and so is an access token`() =
        socketApp {
            val ticket = ticket()
            watch(ticket) {
                subscribe(api().created(guest).id)
                receiveTrip().shouldBeInstanceOf<Subscribed>()
            }

            refused { webSocket("$TRIP?ticket=$ticket") {} }
            refused { webSocket(TRIP, request = { bearerAuth(TestTokens.operator()) }) {} }
            refused { webSocket("$TRIP?token=${TestTokens.operator()}") {} }
        }

    @Test
    fun `an unknown trip is not found, and the socket stays open`() =
        socketApp {
            val trip = api().created(guest)

            watch(ticket()) {
                subscribe(UUID.randomUUID().toString())
                receiveTrip().shouldBeInstanceOf<ErrorMessage>().code shouldBe WsErrorCodes.TRIP_NOT_FOUND

                subscribe(trip.id)
                receiveTrip().shouldBeInstanceOf<Subscribed>()
            }
        }

    @Test
    fun `the guest socket stays for guests, and a staff token there closes it with 4403`() =
        socketApp {
            wsClient().webSocket("/ws/v1/guest", request = { bearerAuth(TestTokens.operator()) }) {
                closeReason.await()?.code shouldBe WsCloseCodes.FORBIDDEN_ROLE
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private fun SocketTestApp.api() = TripApi(client)

    private fun SocketTestApp.bus(): LiveUpdateBus = application.get()

    private fun SocketTestApp.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun SocketTestApp.watch(
        ticket: String,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) = wsClient().webSocket("$TRIP?ticket=$ticket") { withTimeout(10.seconds) { block() } }

    /** The upgrade is answered 401, so the client never gets a socket. */
    private suspend fun SocketTestApp.refused(connect: suspend HttpClient.() -> Unit) {
        shouldThrowAny { wsClient().connect() }.message shouldContain "401"
    }

    /**
     * Waits until the socket's watcher collects the trip's updates. Updates published before would reach it only as
     * the bus's one replayed latest update.
     */
    private suspend fun SocketTestApp.awaitWatcher(tripId: UUID) {
        withTimeout(5.seconds) { while (bus().subscriberCount(tripId) == 0) delay(20) }
    }

    private suspend fun DefaultClientWebSocketSession.subscribe(tripId: String) =
        send(Frame.Text(ProtocolJson.encodeToString(GuestClientMessage.serializer(), Subscribe(tripId))))

    /** The next message; `error` frames belong to the shared ServerMessage family. */
    private suspend fun DefaultClientWebSocketSession.receiveTrip(): Any {
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
            ProtocolJson.decodeFromString(GuestServerMessage.serializer(), text)
        }
    }

    /** The next position or progress: status events and routes from the accept may arrive first. */
    private suspend fun DefaultClientWebSocketSession.receiveSkippingEvents(): Any {
        while (true) {
            val message = receiveTrip()
            if (message !is TripEvent && message !is RouteUpdated) return message
        }
    }

    /** The next `trip_event` with [status], skipping everything else. */
    private suspend fun DefaultClientWebSocketSession.receiveStatus(status: String): TripEvent {
        while (true) {
            val message = receiveTrip()
            if (message is TripEvent && message.status == status) return message
        }
    }

    private companion object {
        const val TRIP = "/ws/v1/admin/trip"
    }
}
