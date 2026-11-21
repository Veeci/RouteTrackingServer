package veeci.practicing.rts.fleet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.fleet.domain.Bbox
import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetDriver
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The newer-wins rules of the fleet read model (phase 11b), without a database. The rule for positions and device
 * statuses runs in SQL since fleet lag fix A1: FleetProjectionIT tests it.
 */
class FleetDriverTest {
    private val t0 = Instant.parse("2026-11-17T08:00:00Z")
    private val empty = FleetDriver(UUID.randomUUID())
    private val tripA = UUID.randomUUID()
    private val tripB = UUID.randomUUID()

    private fun position(at: Instant) = DriverPosition(21.0, 105.8, 12.3, 90.0, at)

    @Test
    fun `last seen is the newer of the position and the device status`() {
        val driver = empty.copy(position = position(t0), device = DeviceState(80, false, "STILL", t0 + 5.seconds))

        driver.lastSeenAt shouldBe t0 + 5.seconds
        driver.copy(device = null).lastSeenAt shouldBe t0
        empty.lastSeenAt.shouldBeNull()
    }

    @Test
    fun `online means heard from within the stale time, the boundary is offline`() {
        val driver = empty.copy(heardAt = t0)

        driver.isOnline(t0 + 59.seconds, 60.seconds) shouldBe true
        driver.isOnline(t0 + 60.seconds, 60.seconds) shouldBe false
        empty.isOnline(t0, 60.seconds) shouldBe false
    }

    @Test
    fun `online reads the server's clock, so a phone 2 minutes slow is online and a fresh position never heard is not`() {
        // The phone's clock runs 2 minutes behind, so its newest position looks 2 minutes old to the server.
        val slowPhone = empty.copy(position = position(t0 - 2.minutes), heardAt = t0)
        val neverHeard = empty.copy(position = position(t0))

        slowPhone.isOnline(t0 + 1.seconds, 60.seconds) shouldBe true
        slowPhone.lastSeenAt shouldBe t0 - 2.minutes
        neverHeard.isOnline(t0 + 1.seconds, 60.seconds) shouldBe false
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
