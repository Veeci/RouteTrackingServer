package veeci.practicing.rts

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.koin.dsl.binds
import org.koin.dsl.module
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.live.adapter.out.bus.InMemoryLiveUpdateBus
import veeci.practicing.rts.live.application.api.DriverPositionUpdate
import veeci.practicing.rts.live.application.api.LiveUpdate
import veeci.practicing.rts.live.application.api.LiveUpdates
import veeci.practicing.rts.live.application.port.out.LiveUpdateBus
import veeci.practicing.rts.protocol.GuestServerMessage
import veeci.practicing.rts.protocol.Subscribed
import veeci.practicing.rts.protocol.TripEvent
import veeci.practicing.rts.protocol.TripProgress
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.simulator.DriverClient
import veeci.practicing.rts.simulator.GuestClient
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.trip.TripApi
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A guest watches a trip while the simulator's driver drives a recorded route: REST, both sockets, the relay. */
@Testcontainers(disabledWithoutDocker = true)
class TripLiveE2ETest {
    private val guestId = TestTokens.newUser()
    private val driverId = TestTokens.newUser()
    private val guest = TestTokens.guest(guestId)
    private val driver = TestTokens.driver(driverId)
    private val route = routeFixes("routes/straight_2km.gpx")

    /** The guest waits where the 2 km route ends. */
    private val pickup = route.last().let { GeoPoint(it.lat, it.lng) }

    @Test
    fun `TC-3-E2E-01 TC-4-E2E-01 the guest sees the driver come closer, arriving, then arrived without the driver tapping`() =
        socketApp {
            val trip = api().created(guest, pickup = pickup, destination = GeoPoint(10.80, 106.70))
            api().act(trip.id, "accept", driver)
            val messages = ConcurrentLinkedQueue<GuestServerMessage>()

            coroutineScope {
                val watching = async { watch(trip.id, messages) }
                awaitSubscribed(messages)
                driverClient().apply { route.chunked(10).forEach(::enqueue) }.connectAndSend()
                eventually { lastProgress(messages)!!.remainingM shouldBeLessThan 50.0 }
                // Phase 4: the server marks the trip arrived by itself; the driver only starts and completes.
                eventually { messages.filterIsInstance<TripEvent>().map { it.status } shouldContain "ARRIVED" }
                api().moved(trip.id, driver, "start", "complete")
                watching.await()
            }

            // Only the way to the pickup: a batch the relay delivers after `start` measures to the destination.
            val remaining = messages.filterIsInstance<TripProgress>().filter { it.target == "PICKUP" }.map { it.remainingM }
            remaining.shouldNotBeEmpty()
            // Each update is at most a GPS accuracy radius (5 m) worse than the one before: the driver comes closer.
            remaining.zipWithNext().filter { (before, after) -> after > before + 5.0 }.shouldBeEmpty()
            messages.filterIsInstance<TripEvent>().last().status shouldBe "COMPLETED"
            val events = messages.filterIsInstance<TripEvent>().map { it.event to it.status }
            events shouldContain (TripEvent.DRIVER_ARRIVING to "ACCEPTED")
            events shouldContain (TripEvent.STATUS_CHANGED to "ARRIVED")
            events.indexOf(TripEvent.DRIVER_ARRIVING to "ACCEPTED") shouldBeLessThan events.indexOf(TripEvent.STATUS_CHANGED to "ARRIVED")
            events.count { it.first == TripEvent.DRIVER_ARRIVING } shouldBe 1
        }

    @Test
    fun `TC-3-E2E-02 a guest who cancels gets CANCELLED, and the driver's later batches are stored but not shown`() {
        val bus = RecordingBus()
        socketApp(overrides = listOf(module { single { bus } binds arrayOf(LiveUpdateBus::class, LiveUpdates::class) })) {
            val trip = api().created(guest, pickup = pickup, destination = GeoPoint(10.80, 106.70))
            api().act(trip.id, "accept", driver)
            val messages = ConcurrentLinkedQueue<GuestServerMessage>()
            val client = driverClient().apply { route.chunked(10).forEach(::enqueue) }

            coroutineScope {
                val watching = async { watch(trip.id, messages) }
                awaitSubscribed(messages)
                client.connectAndSend(maxBatches = 5)
                eventually { messages.filterIsInstance<TripProgress>().shouldNotBeEmpty() }
                api().act(trip.id, "cancel", guest)
                watching.await() // the server closes the guest socket after CANCELLED
            }
            awaitRelayIdle()
            val positionsBefore = bus.positions(trip.id)
            client.connectAndSend()
            awaitRelayIdle()

            client.pending shouldBe 0 // every batch acked and stored
            StoredTrack(client.sessionId).seqs() shouldBe (1L..17L).toList()
            messages.filterIsInstance<TripEvent>().last().status shouldBe "CANCELLED"
            bus.positions(trip.id) shouldBe positionsBefore // nothing new for a cancelled trip
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private fun SocketTestApp.api() = TripApi(client)

    private fun SocketTestApp.driverClient() =
        DriverClient(createClient { install(WebSockets) }, DRIVER_PATH, "e2e-trip-driver", token = driver)

    private suspend fun SocketTestApp.watch(
        tripId: String,
        into: ConcurrentLinkedQueue<GuestServerMessage>,
    ) = GuestClient(createClient { install(WebSockets) }, GUEST_PATH, guest).watch(tripId) { into += it }

    private suspend fun awaitSubscribed(messages: ConcurrentLinkedQueue<GuestServerMessage>) =
        eventually { messages.filterIsInstance<Subscribed>().shouldNotBeEmpty() }

    private fun lastProgress(messages: ConcurrentLinkedQueue<GuestServerMessage>) = messages.filterIsInstance<TripProgress>().lastOrNull()

    /** Waits until the relay has delivered every stored event. */
    private suspend fun awaitRelayIdle() = eventually { unpublishedEvents() shouldBe 0 }

    private fun unpublishedEvents(): Long {
        val env = TestDatabase.env()
        return DriverManager.getConnection(env["DB_URL"], env["DB_USER"], env["DB_PASSWORD"]).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM outbox WHERE published_at IS NULL").use {
                    it.next().let { _ ->
                        it.getLong(1)
                    }
                }
            }
        }
    }

    /** Retries [assertion] for up to 10 s: live updates arrive on other coroutines. */
    private suspend fun eventually(assertion: suspend () -> Unit) {
        withTimeout(10.seconds) {
            while (runCatching { assertion() }.isFailure) delay(20.milliseconds)
        }
        assertion()
    }

    /** The real bus, plus a record of everything published to it. */
    private class RecordingBus(
        private val inner: InMemoryLiveUpdateBus = InMemoryLiveUpdateBus(),
    ) : LiveUpdateBus by inner {
        private val published = ConcurrentLinkedQueue<LiveUpdate>()

        override fun publish(update: LiveUpdate) {
            published += update
            inner.publish(update)
        }

        fun positions(trip: String) = published.count { it is DriverPositionUpdate && it.tripId == UUID.fromString(trip) }
    }

    private companion object {
        const val GUEST_PATH = "/ws/v1/guest"
    }
}
