package veeci.practicing.rts.live

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.koin.dsl.binds
import org.koin.dsl.module
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.live.adapter.out.bus.InMemoryLiveUpdateBus
import veeci.practicing.rts.live.application.api.LiveUpdates
import veeci.practicing.rts.live.application.port.out.LiveUpdateBus
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.BatteryDto
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.protocol.CommandAck
import veeci.practicing.rts.protocol.DeviceStatus
import veeci.practicing.rts.protocol.DriverPosition
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.protocol.GuestClientMessage
import veeci.practicing.rts.protocol.GuestServerMessage
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Subscribe
import veeci.practicing.rts.protocol.Subscribed
import veeci.practicing.rts.protocol.TripEvent
import veeci.practicing.rts.protocol.TripProgress
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import veeci.practicing.rts.testing.trip.trip
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The guest endpoint end to end: REST, the driver socket and the guest socket against one application. */
@Testcontainers(disabledWithoutDocker = true)
class GuestSocketIT {
    private val guestId = TestTokens.newUser()
    private val driverId = TestTokens.newUser()
    private val guest = TestTokens.guest(guestId)
    private val driver = TestTokens.driver(driverId)

    @Test
    fun `TC-3-GWS-01 a guest subscribes to their trip and gets it as it is now`() =
        socketApp {
            val trip = api().created(guest)

            watch(guest) {
                subscribe(trip.id)

                val subscribed = receiveGuest().shouldBeInstanceOf<Subscribed>()
                subscribed.trip.tripId shouldBe trip.id
                subscribed.trip.status shouldBe "REQUESTED"
                subscribed.trip.pickup.lat shouldBe PICKUP.lat
            }
        }

    @Test
    fun `TC-3-GWS-02 another guest's trip is not found, and the socket stays open`() =
        socketApp {
            val someoneElses = api().created(TestTokens.guest())
            val own = api().created(guest)

            watch(guest) {
                subscribe(someoneElses.id)
                receiveGuest().shouldBeInstanceOf<ErrorMessage>().code shouldBe WsErrorCodes.TRIP_NOT_FOUND

                subscribe(own.id)
                receiveGuest().shouldBeInstanceOf<Subscribed>()
            }
        }

    @Test
    fun `TC-4-WS-03 the guest is told the driver is arriving, then sees ARRIVED without the driver tapping`() =
        socketApp {
            val trip = api().created(guest)
            api().act(trip.id, "accept", driver)

            watch(guest) {
                subscribe(trip.id)
                receiveGuest().shouldBeInstanceOf<Subscribed>()

                sendDriverBatch(metersFromPickup = 150.0) // fixes at 170, 160 and 150 m
                val arriving = receiveEvent(TripEvent.DRIVER_ARRIVING)
                arriving.status shouldBe "ACCEPTED"
                (arriving.remainingM!! in 155.0..165.0) shouldBe true // the second fix in a row within 200 m

                // fixes at 25, 15 and 5 m, all after the first batch's fixes
                sendDriverBatch(metersFromPickup = 5.0, endsAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()) + 3.seconds)
                receiveEvent(TripEvent.STATUS_CHANGED).status shouldBe "ARRIVED"
            }
            api().get(trip.id, guest).trip().status shouldBe "ARRIVED"
            client.get("/metrics").bodyAsText() shouldContain """rts_trip_events_total{type="driver_arriving"}"""
        }

    @Test
    fun `TC-4-WS-01 a device status at 10 percent tells the guest the driver's battery is low`() =
        socketApp {
            val trip = api().created(guest)
            api().act(trip.id, "accept", driver)

            watch(guest) {
                subscribe(trip.id)
                receiveGuest().shouldBeInstanceOf<Subscribed>()

                sendDeviceStatus(levelPercent = 10)

                val low = receiveEvent(TripEvent.DRIVER_LOW_BATTERY)
                low.levelPercent shouldBe 10
                low.status shouldBe "ACCEPTED"
            }
        }

    @Test
    fun `TC-4-E2E-02 a driver silent for the stale time is reported gone, then back when heard again`() =
        socketApp(env = mapOf("EVENTS_STALEAFTER" to "1s", "EVENTS_STALECHECKEVERY" to "200ms")) {
            val trip = api().created(guest)
            api().act(trip.id, "accept", driver)

            watch(guest) {
                subscribe(trip.id)
                receiveGuest().shouldBeInstanceOf<Subscribed>()
                sendDeviceStatus(levelPercent = 80) // the driver is heard now

                val stale = receiveEvent(TripEvent.DRIVER_STALE) // about 1 s later
                stale.lastSeenAt.shouldNotBeNull()

                sendDeviceStatus(levelPercent = 80)
                receiveEvent(TripEvent.DRIVER_BACK).status shouldBe "ACCEPTED"
            }
        }

    @Test
    fun `TC-3-GWS-03 a batch from the assigned driver reaches the guest as position, then progress`() =
        socketApp {
            val trip = api().created(guest)
            api().act(trip.id, "accept", driver)

            watch(guest) {
                subscribe(trip.id)
                receiveGuest().shouldBeInstanceOf<Subscribed>().trip.status shouldBe "ACCEPTED"
                sendDriverBatch(metersFromPickup = 800.0)

                val position = receiveSkippingEvents().shouldBeInstanceOf<DriverPosition>()
                val progress = receiveSkippingEvents().shouldBeInstanceOf<TripProgress>()
                position.tripId shouldBe trip.id
                progress.target shouldBe "PICKUP"
                progress.basis shouldBe "STRAIGHT_LINE"
                (progress.remainingM in 790.0..810.0) shouldBe true
            }
        }

    @Test
    fun `TC-3-GWS-04 when the trip is completed the guest gets the event, then the socket closes with 1000`() =
        socketApp {
            val trip = api().created(guest)
            api().moved(trip.id, driver, "accept", "arrive", "start")

            watch(guest) {
                subscribe(trip.id)
                receiveGuest().shouldBeInstanceOf<Subscribed>()
                api().act(trip.id, "complete", driver)

                val completed = receiveSkippingStatus("IN_PROGRESS", "ARRIVED", "ACCEPTED")
                completed.shouldBeInstanceOf<TripEvent>().status shouldBe "COMPLETED"
                completed.event shouldBe TripEvent.STATUS_CHANGED
                withTimeout(5.seconds) { closeReason.await() }?.code shouldBe CloseReason.Codes.NORMAL.code
            }
        }

    @Test
    fun `TC-3-GWS-05 a guest who leaves is no longer a subscriber`() {
        val bus = InMemoryLiveUpdateBus()
        val ownBus = module { single { bus } binds arrayOf(LiveUpdateBus::class, LiveUpdates::class) }
        socketApp(overrides = listOf(ownBus)) {
            val trip = api().created(guest)
            val tripId = UUID.fromString(trip.id)

            watch(guest) {
                subscribe(trip.id)
                receiveGuest().shouldBeInstanceOf<Subscribed>()
                eventually { bus.subscriberCount(tripId) shouldBe 1 }
            }

            eventually { bus.subscriberCount(tripId) shouldBe 0 }
        }
    }

    @Test
    fun `a driver token on the guest socket closes it with 4403`() =
        socketApp {
            wsClient().webSocket(GUEST, request = { bearerAuth(driver) }) {
                closeReason.await()?.code shouldBe WsCloseCodes.FORBIDDEN_ROLE
            }
        }

    @Test
    fun `a socket watches one trip only`() =
        socketApp {
            val trip = api().created(guest)

            watch(guest) {
                subscribe(trip.id)
                receiveGuest().shouldBeInstanceOf<Subscribed>()
                subscribe(trip.id)

                receiveSkippingEvents().shouldBeInstanceOf<ErrorMessage>().code shouldBe WsErrorCodes.INVALID_MESSAGE
            }
        }

    @Test
    fun `a finished trip is answered with its last state, then the socket closes`() =
        socketApp {
            val trip = api().created(guest)
            api().act(trip.id, "cancel", guest)

            watch(guest) {
                subscribe(trip.id)

                receiveGuest().shouldBeInstanceOf<Subscribed>().trip.status shouldBe "CANCELLED"
                closeReason.await()?.code shouldBe CloseReason.Codes.NORMAL.code
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private fun SocketTestApp.api() = TripApi(client)

    private fun SocketTestApp.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun SocketTestApp.watch(
        token: String,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) = wsClient().webSocket(GUEST, request = { bearerAuth(token) }) { withTimeout(10.seconds) { block() } }

    private suspend fun DefaultClientWebSocketSession.subscribe(tripId: String) =
        send(Frame.Text(ProtocolJson.encodeToString(GuestClientMessage.serializer(), Subscribe(tripId))))

    /** The next guest message; `error` frames belong to the shared ServerMessage family. */
    private suspend fun DefaultClientWebSocketSession.receiveGuest(): Any {
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

    /** Status events from earlier changes may arrive first (the bus replays the latest update); skip them. */
    private suspend fun DefaultClientWebSocketSession.receiveSkippingEvents(): Any {
        while (true) {
            val message = receiveGuest()
            if (message !is TripEvent) return message
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveSkippingStatus(vararg earlier: String): TripEvent {
        while (true) {
            val message = receiveGuest()
            if (message is TripEvent && message.status !in earlier) return message
        }
    }

    /** The next `trip_event` with [event], skipping everything else (positions, progress, older events). */
    private suspend fun DefaultClientWebSocketSession.receiveEvent(event: String): TripEvent {
        while (true) {
            val message = receiveGuest()
            if (message is TripEvent && message.event == event) return message
        }
    }

    /**
     * The assigned driver sends one batch of 3 fixes, [metersFromPickup] south of the pickup, over their own socket.
     * The fixes are 1 s apart and the last is 1 s before [endsAt]; a later batch needs a later [endsAt], or the
     * rules skip its fixes as repeats.
     */
    private suspend fun SocketTestApp.sendDriverBatch(
        metersFromPickup: Double,
        endsAt: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) {
        val now = endsAt
        val fixes =
            (0..2).map {
                FixDto(
                    PICKUP.lat - (metersFromPickup + 20 - it * 10) / 111_195.0,
                    PICKUP.lng,
                    accuracyM = 5.0,
                    speedMps = 10.0,
                    recordedAt =
                        now - (3 - it).seconds,
                )
            }
        wsClient().webSocket(DRIVER, request = { bearerAuth(driver) }) {
            sendDriver(Hello(1, "phone-1", UUID.randomUUID().toString(), sdkVersion = "0.9.0"))
            receiveDriver().shouldBeInstanceOf<Welcome>()
            sendDriver(FixBatch(1, fixes))
            receiveDriver().shouldBeInstanceOf<Ack>().accepted shouldBe 3
        }
    }

    /** The assigned driver opens a session and reports its battery over their own socket. */
    private suspend fun SocketTestApp.sendDeviceStatus(levelPercent: Int) {
        wsClient().webSocket(DRIVER, request = { bearerAuth(driver) }) {
            sendDriver(Hello(1, "phone-1", UUID.randomUUID().toString(), sdkVersion = "0.9.0"))
            receiveDriver().shouldBeInstanceOf<Welcome>()
            sendDriver(
                DeviceStatus(Instant.fromEpochMilliseconds(System.currentTimeMillis()), BatteryDto(levelPercent, isCharging = false)),
            )
            // device_status gets no reply. An ack for an unknown command does, and the server reads in order, so
            // that reply proves the status was handled before this socket closes.
            sendDriver(CommandAck(UUID.randomUUID().toString(), CommandAck.APPLIED))
            receiveDriver().shouldBeInstanceOf<ErrorMessage>()
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendDriver(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    /** The next driver message that is not a `command` (phase 5 policies send commands after welcome). */
    private suspend fun DefaultClientWebSocketSession.receiveDriver(): ServerMessage {
        while (true) {
            val message = ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())
            if (message !is Command) return message
        }
    }

    /** Retries [assertion] for up to 5 s: subscriber counts change on other coroutines. */
    private suspend fun eventually(assertion: () -> Unit) {
        withTimeout(5.seconds) {
            while (runCatching(assertion).isFailure) delay(20)
        }
        assertion()
    }

    private companion object {
        const val GUEST = "/ws/v1/guest"
        const val DRIVER = "/ws/v1/driver"
    }
}
