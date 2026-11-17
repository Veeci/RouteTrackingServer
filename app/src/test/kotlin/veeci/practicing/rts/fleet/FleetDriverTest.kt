package veeci.practicing.rts.fleet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.fleet.domain.Bbox
import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetDriver
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The newer-wins rules of the fleet read model (phase 11b), without a database. */
class FleetDriverTest {
    private val t0 = Instant.parse("2026-11-17T08:00:00Z")
    private val empty = FleetDriver(UUID.randomUUID())
    private val tripA = UUID.randomUUID()
    private val tripB = UUID.randomUUID()

    private fun position(at: Instant) = DriverPosition(21.0, 105.8, 12.3, 90.0, at)

    @Test
    fun `a newer position replaces the stored one, an equal or older one changes nothing`() {
        val stored = checkNotNull(empty.withPosition(position(t0 + 10.seconds)))

        stored.withPosition(position(t0 + 10.seconds)).shouldBeNull() // the same event again
        stored.withPosition(position(t0)).shouldBeNull() // a late, older event
        stored.withPosition(position(t0 + 11.seconds))?.position?.at shouldBe t0 + 11.seconds
    }

    @Test
    fun `last seen is the newer of the position and the device status`() {
        val driver =
            checkNotNull(empty.withPosition(position(t0)))
                .withDevice(DeviceState(80, false, "STILL", t0 + 5.seconds))
                .shouldNotBeNull()

        driver.lastSeenAt shouldBe t0 + 5.seconds
        driver.withDevice(DeviceState(79, false, null, t0 + 5.seconds)).shouldBeNull()
    }

    @Test
    fun `online means heard from within the stale time, the boundary is offline`() {
        val driver = checkNotNull(empty.withPosition(position(t0)))

        driver.isOnline(t0 + 59.seconds, 60.seconds) shouldBe true
        driver.isOnline(t0 + 60.seconds, 60.seconds) shouldBe false
        empty.isOnline(t0, 60.seconds) shouldBe false
    }

    @Test
    fun `a trip sets the current trip and its end clears it`() {
        val accepted = checkNotNull(empty.withTripStage(tripA, "ACCEPTED", ended = false, at = t0))
        accepted.trip?.currentTripId shouldBe tripA
        accepted.onTrip shouldBe true

        val completed = checkNotNull(accepted.withTripStage(tripA, "COMPLETED", ended = true, at = t0 + 60.seconds))
        completed.trip?.currentTripId.shouldBeNull()
        completed.trip?.status shouldBe "COMPLETED"
        completed.onTrip shouldBe false
    }

    @Test
    fun `an older trip stage, or the end of another trip, never hides the current trip`() {
        val onB = checkNotNull(empty.withTripStage(tripB, "ACCEPTED", ended = false, at = t0 + 60.seconds))

        onB.withTripStage(tripB, "ACCEPTED", ended = false, at = t0 + 60.seconds).shouldBeNull() // repeated
        onB.withTripStage(tripA, "COMPLETED", ended = true, at = t0).shouldBeNull() // older
        onB.withTripStage(tripA, "CANCELLED", ended = true, at = t0 + 90.seconds).shouldBeNull() // another trip
    }

    @Test
    fun `the account wins whenever it differs, and an equal account changes nothing`() {
        val named = checkNotNull(empty.withAccount("Lan", enabled = true))

        named.withAccount("Lan", enabled = true).shouldBeNull()
        named.withAccount("Lan", enabled = false)?.enabled shouldBe false
    }

    @Test
    fun `a bbox needs the minimum first and stays within the world`() {
        Bbox(105.8, 21.0, 105.9, 21.1).minLng shouldBe 105.8
        shouldThrow<IllegalArgumentException> { Bbox(170.0, 0.0, -170.0, 1.0) } // across the antimeridian
        shouldThrow<IllegalArgumentException> { Bbox(0.0, 0.0, 1.0, 91.0) }
    }
}
