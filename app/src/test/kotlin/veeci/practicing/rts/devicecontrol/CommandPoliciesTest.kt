package veeci.practicing.rts.devicecontrol

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.devicecontrol.application.CommandPolicies
import veeci.practicing.rts.devicecontrol.application.CommandService
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.devicecontrol.domain.TrackingAccuracy
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.devicecontrol.InMemoryCommandEventOutbox
import veeci.practicing.rts.testing.devicecontrol.InMemoryDeviceCommandRepository
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.event.TripStatusChanged
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinInstant

class CommandPoliciesTest {
    private class MovingClock(
        var now: Instant = Instant.parse("2026-11-13T08:00:00Z"),
    ) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId?) = this

        fun advance(by: Duration) {
            now += by.toJavaDuration()
        }
    }

    private val clock = MovingClock()
    private val repository = InMemoryDeviceCommandRepository()
    private val service = CommandService(repository, InMemoryCommandEventOutbox(), DirectTransactionRunner, clock, 10.minutes)
    private val policies = CommandPolicies(service, repository, DirectTransactionRunner, clock)
    private val driver = UserId(UUID.randomUUID())
    private val pickup = GeoPoint(10.7725, 106.698)
    private val destination = GeoPoint(10.7798, 106.699)

    private suspend fun sent() = repository.latestFor(driver, 50).reversed().map { it.payload }

    private fun status(to: TripStatus) =
        TripStatusChanged(
            UUID.randomUUID(),
            UUID.randomUUID(),
            driver.value,
            null,
            to,
            clock.instant().toKotlinInstant(),
            pickup = pickup,
            destination = destination,
        )

    @Test
    fun `TC-5-POL-01 the first guest watching gets the driver a high accuracy constraint`() =
        runTest {
            policies.firstGuestWatching(driver)

            sent() shouldBe listOf(CommandPayload.SetConstraint(TrackingAccuracy.HIGH, 1_000, "guest_watching"))
        }

    @Test
    fun `TC-5-POL-02 a guest reconnecting 5 times within 10 s still gives one constraint`() =
        runTest {
            repeat(5) {
                policies.firstGuestWatching(driver)
                clock.advance(1.seconds)
                policies.lastGuestLeft(driver, rideStarted = false)
                clock.advance(1.seconds)
            }

            sent().size shouldBe 1
        }

    @Test
    fun `the last guest leaving after 10 s lowers the accuracy, but not during the ride`() =
        runTest {
            policies.firstGuestWatching(driver)
            clock.advance(11.seconds)

            policies.lastGuestLeft(driver, rideStarted = true)
            sent().size shouldBe 1

            policies.lastGuestLeft(driver, rideStarted = false)
            sent().last() shouldBe CommandPayload.SetConstraint(TrackingAccuracy.BALANCED, 5_000, "no_viewers")
        }

    @Test
    fun `a guest returning right after a lowered accuracy raises it again at once`() =
        runTest {
            policies.firstGuestWatching(driver)
            clock.advance(11.seconds)
            policies.lastGuestLeft(driver, rideStarted = false)
            clock.advance(1.seconds)

            policies.firstGuestWatching(driver)

            sent().map { (it as CommandPayload.SetConstraint).maxAccuracy } shouldBe
                listOf(TrackingAccuracy.HIGH, TrackingAccuracy.BALANCED, TrackingAccuracy.HIGH)
        }

    @Test
    fun `TC-5-POL-03 an accepted trip registers a 150 m geofence at the pickup`() =
        runTest {
            policies.onTripStatus(status(TripStatus.ACCEPTED))

            val fences = sent().single().shouldBeInstanceOf<CommandPayload.RegisterGeofences>()
            fences.regions.single().center shouldBe pickup
            fences.regions.single().radiusM shouldBe 150.0
        }

    @Test
    fun `a started ride registers a geofence at the destination`() =
        runTest {
            policies.onTripStatus(status(TripStatus.IN_PROGRESS))

            sent()
                .single()
                .shouldBeInstanceOf<CommandPayload.RegisterGeofences>()
                .regions
                .single()
                .center shouldBe destination
        }

    @Test
    fun `TC-5-POL-04 a completed trip sets a passive constraint, even right after a high one`() =
        runTest {
            policies.firstGuestWatching(driver)

            policies.onTripStatus(status(TripStatus.COMPLETED))

            sent().last() shouldBe CommandPayload.SetConstraint(TrackingAccuracy.PASSIVE, 60_000, "trip_ended")
        }

    @Test
    fun `a trip without a driver gives no command`() =
        runTest {
            policies.onTripStatus(status(TripStatus.CANCELLED).copy(driverId = null))

            sent().shouldBeEmpty()
        }
}
