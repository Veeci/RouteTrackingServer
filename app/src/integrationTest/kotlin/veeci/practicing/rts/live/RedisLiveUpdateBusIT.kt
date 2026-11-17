package veeci.practicing.rts.live

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.live.adapter.out.bus.RedisLiveUpdateBus
import veeci.practicing.rts.live.application.api.DriverPositionUpdate
import veeci.practicing.rts.live.application.api.TripStatusUpdate
import veeci.practicing.rts.platform.config.RedisConfig
import veeci.practicing.rts.platform.redis.RedisConnections
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.TestRedis
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/** TC-10-RED-01: two buses on one Redis act like two instances. A guest on either sees what the other published. */
@Testcontainers(disabledWithoutDocker = true)
class RedisLiveUpdateBusIT {
    private val connections = mutableListOf<RedisConnections>()
    private val trip = UUID.randomUUID()
    private val t0 = Instant.parse("2026-11-17T08:00:00Z")

    private fun instance(url: String = TestRedis.url()): RedisLiveUpdateBus =
        RedisLiveUpdateBus(RedisConnections(RedisConfig(url, publishTimeout = 300.milliseconds)).also { connections += it })

    private fun position(
        n: Int,
        trip: UUID = this.trip,
    ) = DriverPositionUpdate(trip, GeoPoint(21.0 + n * 1e-4, 105.8), 90.0, 10.0, t0 + n.seconds)

    @AfterEach
    fun close() = connections.forEach { it.close() }

    @Test
    fun `TC-10-RED-01 updates published on one instance reach a guest on another, in order`() =
        runBlocking<Unit> {
            val a = instance()
            val b = instance()
            val received = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { b.subscribe(trip).take(3).toList() }
            eventually { a.isWatched(trip) shouldBe true } // B's subscription is in place

            (1..3).forEach { a.publish(position(it)) }

            withTimeout(5.seconds) { received.await() } shouldBe (1..3).map { position(it) }
        }

    @Test
    fun `a guest who subscribes late gets the trip's latest update from Redis`() =
        runBlocking<Unit> {
            val a = instance()
            val b = instance()
            a.publish(position(1))
            a.publish(TripStatusUpdate(trip, TripStatus.IN_PROGRESS, t0))
            delay(200) // the publish is fire and forget

            withTimeout(5.seconds) { b.subscribe(trip).first() } shouldBe TripStatusUpdate(trip, TripStatus.IN_PROGRESS, t0)
        }

    @Test
    fun `updates of another trip never reach the guest, and the watch count follows the guests of every instance`() =
        runBlocking<Unit> {
            val a = instance()
            val b = instance()
            val other = UUID.randomUUID()
            a.isWatched(trip) shouldBe false

            val watching = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { b.subscribe(trip).first() }
            eventually { a.isWatched(trip) shouldBe true }
            a.publish(position(1, trip = other))
            a.publish(position(2))

            withTimeout(5.seconds) { watching.await() } shouldBe position(2)
            eventually { a.isWatched(trip) shouldBe false } // the guest left with first(): B unsubscribed
        }

    @Test
    fun `TC-10-RED-02 with Redis gone, publish neither throws nor waits, and nobody counts as watching`() =
        runBlocking<Unit> {
            val redis = TestRedis.newContainer().apply { start() }
            val bus = instance(TestRedis.urlOf(redis))
            redis.stop()

            val started = TimeSource.Monotonic.markNow()
            repeat(100) { bus.publish(position(it)) }
            started.elapsedNow() shouldBeLessThan 1.seconds
            bus.isWatched(trip) shouldBe false
        }

    private infix fun kotlin.time.Duration.shouldBeLessThan(limit: kotlin.time.Duration) = (this < limit) shouldBe true

    private suspend fun eventually(assertion: suspend () -> Unit) {
        val end = TimeSource.Monotonic.markNow() + 5.seconds
        while (true) {
            try {
                return assertion()
            } catch (e: AssertionError) {
                if (end.hasPassedNow()) throw e
                delay(20)
            }
        }
    }
}
