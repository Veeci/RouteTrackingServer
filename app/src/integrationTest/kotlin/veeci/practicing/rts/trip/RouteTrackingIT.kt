package veeci.practicing.rts.trip

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.protocol.CommandAck
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.protocol.GuestClientMessage
import veeci.practicing.rts.protocol.GuestServerMessage
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.RouteUpdated
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Subscribe
import veeci.practicing.rts.protocol.Subscribed
import veeci.practicing.rts.protocol.TripEvent
import veeci.practicing.rts.protocol.TripProgress
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.MeasuredPolyline
import veeci.practicing.rts.shared.geo.PolylineCodec
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TEST_DRIVER_PACE
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.geo.offset
import veeci.practicing.rts.testing.geo.track
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Phase 7 end to end on Netty, with the test grid: a driver's socket sends fixes made along the routes the server
 * planned, and a guest's socket records what it sees. These stand in for the plan's E2E cases (TC-7-E2E-01, 02).
 */
@Testcontainers(disabledWithoutDocker = true)
class RouteTrackingIT {
    private val guestId = TestTokens.newUser()
    private val guest = TestTokens.guest(guestId)
    private val driver = TestTokens.driver(TestTokens.newUser())

    @Test
    fun `TC-7-E2E-01 a detour gives exactly one re-route, both sides get version 2, and the guest sees road progress`() =
        socketApp {
            val trip = api().created(guest)
            api().moved(trip.id, driver, "accept", "arrive", "start")

            watching(trip.id) { seen ->
                val first = awaitSubscribedRoute(seen)
                drive { d ->
                    // Right after welcome the driver gets the route it follows, without asking.
                    d.awaitRoute { it.routeVersion == 1 }.kind shouldBe "TRIP"
                    val v1 = MeasuredPolyline(PolylineCodec.decode(first.polyline))
                    val along = track(v1, speedMps = 10.0, start = d.clock).filter { it.alongM <= 300.0 }
                    d.send(along.map { fix(it.point, it.at) })
                    d.send(sideways(v1, 300.0, from = d.clock)) // 100 m off the road in 10 fixes

                    val v2 = d.awaitRoute { it.routeVersion == 2 }
                    v2.reason shouldBe RouteUpdated.OFF_ROUTE
                    val line = MeasuredPolyline(PolylineCodec.decode(v2.polyline))
                    d.send(track(line, speedMps = 10.0, start = d.clock).map { fix(it.point, it.at) })
                }

                eventually {
                    val last = seen.lastProgress()
                    last.routeVersion shouldBe 2
                    last.remainingM shouldBeLessThan 50.0
                }
                seen.filterIsInstance<TripEvent>().count { it.event == TripEvent.OFF_ROUTE } shouldBe 1
                // The start's status_changed (version 1) may also arrive, depending on when the relay delivers it.
                seen.filterIsInstance<RouteUpdated>().filter { it.reason == RouteUpdated.OFF_ROUTE }.map { it.routeVersion } shouldBe
                    listOf(2)
                seen.filterIsInstance<TripProgress>().map { it.basis }.toSet() shouldBe setOf("ROUTE")
            }
        }

    @Test
    fun `TC-7-E2E-02 a 45 s gap in the fixes, as in a tunnel, gives no false off-route`() =
        socketApp {
            val trip = api().created(guest)
            api().moved(trip.id, driver, "accept", "arrive", "start")

            watching(trip.id) { seen ->
                val route = awaitSubscribedRoute(seen)
                val line = MeasuredPolyline(PolylineCodec.decode(route.polyline))
                // Slow enough that 45 s of silence fits between 100 m and 100 m before the end.
                val speed = minOf(10.0, (line.lengthM - 200.0) / 45.0)
                drive { d ->
                    val fixes = track(line, speedMps = speed, start = d.clock)
                    val gapStart = fixes.first().at + (100.0 / speed).seconds
                    d.send(fixes.filter { it.at < gapStart || it.at >= gapStart + 45.seconds }.map { fix(it.point, it.at) })
                }

                eventually { seen.lastProgress().remainingM shouldBeLessThan 50.0 }
                delay(1.seconds) // a re-route, if any, would arrive this late
                seen.filterIsInstance<TripEvent>().filter { it.event == TripEvent.OFF_ROUTE }.shouldBeEmpty()
                seen.filterIsInstance<RouteUpdated>().filter { it.reason == RouteUpdated.OFF_ROUTE }.shouldBeEmpty()
            }
        }

    @Test
    fun `the first fix after accept plans the APPROACH route, and starting the ride hands over the TRIP route`() =
        socketApp {
            val trip = api().created(guest)
            api().act(trip.id, "accept", driver)

            watching(trip.id) { seen ->
                awaitSubscribed(seen).trip.route shouldBe null
                drive { d ->
                    d.send(listOf(fix(PICKUP.offset(northM = -150.0), d.clock)))
                    val approach = d.awaitRoute { it.kind == "APPROACH" }
                    approach.routeVersion shouldBe 1
                    approach.reason shouldBe RouteUpdated.INITIAL

                    // Progress before the ride now follows the APPROACH route.
                    d.send(listOf(fix(PICKUP.offset(northM = -140.0), d.clock)))
                    eventually { seen.lastProgress().basis shouldBe "ROUTE" }

                    api().moved(trip.id, driver, "arrive", "start")
                    d.awaitRoute { it.kind == "TRIP" }.reason shouldBe RouteUpdated.STATUS_CHANGED
                }

                eventually {
                    seen.filterIsInstance<RouteUpdated>().map { it.kind to it.reason } shouldBe
                        listOf("APPROACH" to RouteUpdated.INITIAL, "TRIP" to RouteUpdated.STATUS_CHANGED)
                }
            }
        }

    // Helpers

    private fun SocketTestApp.api() = TripApi(client)

    private fun fix(
        point: GeoPoint,
        at: Instant,
    ) = FixDto(point.lat, point.lng, accuracyM = 5.0, speedMps = 10.0, recordedAt = at)

    /** 10 fixes, 1 s apart, going straight away from the route at [alongM]: 10 m a second, 100 m in the end. */
    private fun sideways(
        line: MeasuredPolyline,
        alongM: Double,
        from: Instant,
    ): List<FixDto> {
        val a = line.pointAt(alongM - 1)
        val b = line.pointAt(alongM + 1)
        val heading = atan2((b.lng - a.lng) * cos(Math.toRadians(a.lat)), b.lat - a.lat)
        val start = line.pointAt(alongM)
        return (1..10).map { i ->
            fix(start.offset(northM = -sin(heading) * i * 10, eastM = cos(heading) * i * 10), from + i.seconds)
        }
    }

    /** Watches [tripId] as the guest; every message the guest gets goes into the list [block] sees. */
    private suspend fun SocketTestApp.watching(
        tripId: String,
        block: suspend CoroutineScope.(ConcurrentLinkedQueue<GuestServerMessage>) -> Unit,
    ) = coroutineScope {
        val seen = ConcurrentLinkedQueue<GuestServerMessage>()
        val guestSocket: Job =
            launch {
                createClient { install(WebSockets) }.webSocket(GUEST, request = { bearerAuth(guest) }) {
                    send(Frame.Text(ProtocolJson.encodeToString(GuestClientMessage.serializer(), Subscribe(tripId))))
                    for (frame in incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        runCatching { ProtocolJson.decodeFromString(GuestServerMessage.serializer(), text) }.onSuccess { seen += it }
                    }
                }
            }
        try {
            withTimeout(30.seconds) { block(seen) }
        } finally {
            guestSocket.cancel()
        }
    }

    private suspend fun awaitSubscribed(seen: ConcurrentLinkedQueue<GuestServerMessage>): Subscribed {
        eventually { seen.filterIsInstance<Subscribed>().shouldNotBeEmpty() }
        return seen.filterIsInstance<Subscribed>().first()
    }

    /**
     * The latest `trip_progress` the guest got. It fails as an assertion, not with `NoSuchElementException`, while none
     * has arrived, so that [eventually] retries it.
     */
    private fun ConcurrentLinkedQueue<GuestServerMessage>.lastProgress(): TripProgress =
        filterIsInstance<TripProgress>().lastOrNull().shouldNotBeNull()

    private suspend fun awaitSubscribedRoute(seen: ConcurrentLinkedQueue<GuestServerMessage>) =
        awaitSubscribed(seen).trip.route.shouldNotBeNull()

    /** One driver session for the whole block; fixes are dated from 20 minutes ago on, one second apart. */
    private suspend fun SocketTestApp.drive(block: suspend (DriverSession) -> Unit) =
        createClient { install(WebSockets) }.webSocket(DRIVER, request = { bearerAuth(driver) }) {
            val session = DriverSession(this, Instant.fromEpochMilliseconds(System.currentTimeMillis()) - 20.minutes)
            session.hello()
            block(session)
        }

    /**
     * The driver's side of the socket: sends batches of 10, [TEST_DRIVER_PACE] apart, and keeps every `route_updated`
     * it gets. An `error` frame fails the test at once with the server's code.
     */
    private class DriverSession(
        private val socket: DefaultClientWebSocketSession,
        start: Instant,
    ) {
        private var seq = 0L
        private val routes = mutableListOf<RouteUpdated>()

        /** The time for the next fix: one second after the last one sent. */
        var clock: Instant = start
            private set

        suspend fun hello() {
            sendMessage(Hello(1, "phone-1", UUID.randomUUID().toString(), sdkVersion = "0.9.0"))
            receive().shouldBeInstanceOf<Welcome>()
        }

        suspend fun send(fixes: List<FixDto>) {
            fixes.chunked(BATCH).forEach { batch ->
                delay(TEST_DRIVER_PACE)
                sendMessage(FixBatch(++seq, batch))
                while (true) {
                    val message = next()
                    if (message is Ack && message.seq == seq) break
                }
            }
            clock = fixes.maxOf { it.recordedAt } + 1.seconds
        }

        /** The first `route_updated` that matches, received before or now. */
        suspend fun awaitRoute(match: (RouteUpdated) -> Boolean): RouteUpdated {
            routes.firstOrNull(match)?.let { return it }
            return withTimeout(10.seconds) {
                var found: RouteUpdated? = null
                while (found == null) found = (next() as? RouteUpdated)?.takeIf(match)
                found
            }
        }

        /** The next message; commands get their ack, routes are kept, and an `error` frame throws. */
        private suspend fun next(): ServerMessage =
            receive().also {
                if (it is ErrorMessage) error("The server refused seq ${it.correlatesTo}: ${it.code} ${it.message}")
                if (it is Command) sendMessage(CommandAck(it.commandId, CommandAck.APPLIED))
                if (it is RouteUpdated) routes += it
            }

        private suspend fun receive(): ServerMessage =
            ProtocolJson.decodeFromString(ServerMessage.serializer(), (socket.incoming.receive() as Frame.Text).readText())

        private suspend fun sendMessage(message: ClientMessage) =
            socket.send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))
    }

    private suspend fun eventually(assertion: () -> Unit) {
        val end = System.currentTimeMillis() + EVENTUALLY_MS
        while (true) {
            try {
                return assertion()
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > end) throw e
                delay(POLL_MS)
            }
        }
    }

    private companion object {
        const val GUEST = "/ws/v1/guest"
        const val DRIVER = "/ws/v1/driver"
        const val BATCH = 10
        const val EVENTUALLY_MS = 10_000L
        const val POLL_MS = 50L
    }
}
