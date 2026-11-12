package veeci.practicing.rts.trip.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.trip.domain.event.StatusCause
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.event.TripStatus.ACCEPTED
import veeci.practicing.rts.trip.domain.event.TripStatus.ARRIVED
import veeci.practicing.rts.trip.domain.event.TripStatus.CANCELLED
import veeci.practicing.rts.trip.domain.event.TripStatus.COMPLETED
import veeci.practicing.rts.trip.domain.event.TripStatus.IN_PROGRESS
import veeci.practicing.rts.trip.domain.event.TripStatus.REQUESTED
import veeci.practicing.rts.trip.domain.event.TripStatusChanged
import kotlin.time.Duration.Companion.hours

class TripTest {
    private val guest = TripSamples.guest()
    private val driver = TripSamples.driver()
    private val later = T0 + 1.hours

    /**
     * The legal moves, written here again on purpose instead of reading Trip.TRANSITIONS: a test that reused
     * the production table would agree with any mistake in it.
     */
    private val legal =
        mapOf(
            (REQUESTED to TripAction.ACCEPT) to ACCEPTED,
            (ACCEPTED to TripAction.ARRIVE) to ARRIVED,
            (ARRIVED to TripAction.START) to IN_PROGRESS,
            (IN_PROGRESS to TripAction.COMPLETE) to COMPLETED,
            (REQUESTED to TripAction.CANCEL) to CANCELLED,
            (ACCEPTED to TripAction.CANCEL) to CANCELLED,
            (ARRIVED to TripAction.CANCEL) to CANCELLED,
        )

    @Test
    fun `TC-3-SM-01 every legal move succeeds, records the change and updates the time`() {
        legal.forEach { (move, target) ->
            val (from, action) = move
            withClue("$from + $action") {
                val trip = TripSamples.inStatus(from, guest, driver)

                trip.perform(action, allowedActor(action), later)

                trip.status shouldBe target
                trip.updatedAt shouldBe later
                trip.pullEvents() shouldBe
                    listOf(TripStatusChanged(trip.id.value, guest.userId.value, trip.driverId?.value, from, target, later))
            }
        }
    }

    @Test
    fun `TC-3-SM-02 every move that is not in the table is an illegal transition, whoever tries it`() {
        // All 6 x 5 pairs: checking each one is stronger than sampling them at random.
        TripStatus.entries.forEach { from ->
            TripAction.entries.filter { (from to it) !in legal }.forEach { action ->
                withClue("$from + $action") {
                    val trip = TripSamples.inStatus(from, guest, driver)

                    val error = shouldThrow<DomainException> { trip.perform(action, allowedActor(action), later) }

                    error.code shouldBe TripError.ILLEGAL_TRANSITION
                    trip.status shouldBe from
                    trip.pullEvents() shouldBe emptyList()
                }
            }
        }
    }

    @Test
    fun `TC-3-SM-03 accept assigns the driver who accepted`() {
        val trip = TripSamples.requested(guest)

        trip.perform(TripAction.ACCEPT, driver, later)

        trip.driverId shouldBe driver.userId
    }

    @Test
    fun `TC-3-SM-04 only the assigned driver may start the trip`() {
        val trip = TripSamples.inStatus(ARRIVED, guest, driver)

        shouldThrow<DomainException> { trip.perform(TripAction.START, TripSamples.driver(), later) }.code shouldBe
            TripError.TRIP_ACTION_FORBIDDEN
        trip.status shouldBe ARRIVED
    }

    @ParameterizedTest
    @EnumSource(names = ["REQUESTED", "ACCEPTED", "ARRIVED"])
    fun `TC-3-SM-05 the guest may cancel before the ride starts`(status: TripStatus) {
        val trip = TripSamples.inStatus(status, guest, driver)

        trip.perform(TripAction.CANCEL, guest, later)

        trip.status shouldBe CANCELLED
    }

    @Test
    fun `TC-3-SM-05 the guest may not cancel a ride in progress`() {
        val trip = TripSamples.inStatus(IN_PROGRESS, guest, driver)

        shouldThrow<DomainException> { trip.perform(TripAction.CANCEL, guest, later) }.code shouldBe TripError.ILLEGAL_TRANSITION
    }

    @Test
    fun `TC-3-SM-06 a guest may not accept a trip`() {
        val trip = TripSamples.requested(guest)

        shouldThrow<DomainException> { trip.perform(TripAction.ACCEPT, TripSamples.guest(), later) }.code shouldBe
            TripError.TRIP_ACTION_FORBIDDEN
    }

    @Test
    fun `another guest and an admin may not cancel the trip`() {
        listOf(TripSamples.guest(), TripSamples.admin()).forEach { stranger ->
            val trip = TripSamples.requested(guest)

            shouldThrow<DomainException> { trip.perform(TripAction.CANCEL, stranger, later) }.code shouldBe TripError.TRIP_ACTION_FORBIDDEN
        }
    }

    @Test
    fun `only a guest may request a trip`() {
        listOf(driver, TripSamples.admin()).forEach { actor ->
            shouldThrow<DomainException> { TripSamples.requested(actor) }.code shouldBe TripError.TRIP_ACTION_FORBIDDEN
        }
    }

    @Test
    fun `a new trip records that it was requested`() {
        val trip = Trip.request(guest, TripSamples.PICKUP, TripSamples.DESTINATION, TripId(java.util.UUID.randomUUID()), T0)

        trip.pullEvents() shouldBe listOf(TripStatusChanged(trip.id.value, guest.userId.value, null, null, REQUESTED, T0))
        trip.pullEvents() shouldBe emptyList()
    }

    @Test
    fun `the guest sees their trip, drivers see open trips and their own, admins see every trip`() {
        val open = TripSamples.requested(guest)
        val taken = TripSamples.inStatus(ACCEPTED, guest, driver)

        open.isVisibleTo(guest) shouldBe true
        open.isVisibleTo(TripSamples.guest()) shouldBe false
        open.isVisibleTo(TripSamples.driver()) shouldBe true
        taken.isVisibleTo(driver) shouldBe true
        taken.isVisibleTo(TripSamples.driver()) shouldBe false
        taken.isVisibleTo(TripSamples.admin()) shouldBe true
    }

    @Test
    fun `auto-arrive moves an ACCEPTED trip to ARRIVED and records cause AUTO`() {
        val trip = TripSamples.inStatus(ACCEPTED, driver = driver)
        val at = T0 + 2.hours

        trip.autoArrive(at) shouldBe true

        trip.status shouldBe ARRIVED
        trip.updatedAt shouldBe at
        trip.pullEvents() shouldBe
            listOf(TripStatusChanged(trip.id.value, trip.guestId.value, driver.userId.value, ACCEPTED, ARRIVED, at, StatusCause.AUTO))
    }

    @ParameterizedTest
    @EnumSource(TripStatus::class, names = ["ACCEPTED"], mode = EnumSource.Mode.EXCLUDE)
    fun `auto-arrive changes nothing when the trip is not ACCEPTED`(status: TripStatus) {
        val trip = TripSamples.inStatus(status, driver = driver)

        trip.autoArrive(T0 + 2.hours) shouldBe false

        trip.status shouldBe status
        trip.pullEvents() shouldBe emptyList()
    }

    /** Someone the table allows to do [action], so a refusal can only come from the trip's stage. */
    private fun allowedActor(action: TripAction) = if (action == TripAction.CANCEL) guest else driver
}
