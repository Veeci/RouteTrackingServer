package veeci.practicing.rts.live

import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import veeci.practicing.rts.live.adapter.out.bus.InMemoryLiveUpdateBus
import veeci.practicing.rts.live.application.api.DriverPositionUpdate
import veeci.practicing.rts.live.application.api.TripStatusUpdate
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class InMemoryLiveUpdateBusTest {
    private val bus = InMemoryLiveUpdateBus()
    private val tripA = UUID.randomUUID()
    private val tripB = UUID.randomUUID()
    private val t0 = Instant.parse("2026-10-07T09:00:00Z")

    private fun position(
        trip: UUID,
        i: Int,
    ) = DriverPositionUpdate(trip, GeoPoint(10.77 + i * 0.0001, 106.69), bearingDeg = 0.0, speedMps = 10.0, recordedAt = t0 + i.seconds)

    @Test
    fun `TC-3-HUB-01 a subscriber gets what is published after it subscribed, in order`() =
        runTest {
            bus.subscribe(tripA).test {
                (1..3).forEach { bus.publish(position(tripA, it)) }

                (1..3).forEach { awaitItem() shouldBe position(tripA, it) }
            }
        }

    @Test
    fun `TC-3-HUB-02 a late subscriber gets the latest update at once`() =
        runTest {
            bus.publish(position(tripA, 1))
            bus.publish(position(tripA, 2))

            bus.subscribe(tripA).first() shouldBe position(tripA, 2)
        }

    @Test
    fun `TC-3-HUB-03 updates of trip A never reach a subscriber of trip B`() =
        runTest {
            bus.subscribe(tripB).test {
                bus.publish(position(tripA, 1))
                bus.publish(position(tripB, 1))

                awaitItem() shouldBe position(tripB, 1)
                expectNoEvents()
            }
        }

    @Test
    fun `TC-3-HUB-04 a subscriber that never reads does not slow down publish`() =
        runTest {
            // The collector suspends forever on its first item, like a guest whose network stalls.
            val stalled = backgroundScope.launch { bus.subscribe(tripA).collect { awaitCancellation() } }
            yield()

            withTimeout(1.seconds) { repeat(1_000) { bus.publish(position(tripA, it)) } }

            bus.subscriberCount(tripA) shouldBe 1
            stalled.cancel()
        }

    @Test
    fun `TC-3-HUB-05 a finished trip is dropped when its last subscriber leaves`() =
        runTest {
            val watcher = backgroundScope.launch { bus.subscribe(tripA).collect {} }
            yield()
            bus.publish(TripStatusUpdate(tripA, TripStatus.COMPLETED, t0))
            bus.tripCount shouldBe 1 // still watched

            watcher.cancel()
            watcher.join()

            bus.tripCount shouldBe 0
        }

    @Test
    fun `a finished trip that nobody watches is dropped at once`() =
        runTest {
            bus.publish(position(tripA, 1))
            bus.publish(TripStatusUpdate(tripA, TripStatus.CANCELLED, t0))

            bus.tripCount shouldBe 0
        }
}
