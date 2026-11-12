package veeci.practicing.rts.trip.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.testing.trip.InMemoryArrivalStateStore
import veeci.practicing.rts.testing.trip.InMemoryTripEventOutbox
import veeci.practicing.rts.testing.trip.InMemoryTripMomentRepository
import veeci.practicing.rts.testing.trip.InMemoryTripRepository
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.event.StatusCause
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant

class TripEventServiceTest {
    private val trips = InMemoryTripRepository()
    private val states = InMemoryArrivalStateStore()
    private val moments = InMemoryTripMomentRepository()
    private val outbox = InMemoryTripEventOutbox()
    private val clock = Clock.fixed((T0 + 1.seconds * 600).toJavaInstant(), ZoneOffset.UTC)
    private val driver = TripSamples.driver()

    private fun service(repository: TripRepository = trips) =
        TripEventService(repository, states, moments, outbox, DirectTransactionRunner, clock)

    private suspend fun tripIn(status: TripStatus) = TripSamples.inStatus(status, driver = driver).also { trips.insert(it) }

    /** One batch of the driver: a fix [m] meters south of the pickup every 5 s, starting [startS] s after T0. */
    private fun batch(
        vararg meters: Double,
        startS: Int = 0,
    ) = FixesAccepted(
        sessionId = UUID.randomUUID(),
        deviceId = "phone-1",
        driverId = driver.userId.value,
        seq = 1,
        positions =
            meters.mapIndexed { i, m ->
                FixesAccepted.Position(GeoPoint(PICKUP.lat - m / 111_195.0, PICKUP.lng), T0 + (startS + i * 5).seconds, 8.0, 0.0, 0.0)
            },
    )

    @Test
    fun `arriving is stored and sent through the outbox, with the trip's stage`() =
        runTest {
            val trip = tripIn(TripStatus.ACCEPTED)

            service().onFixesAccepted(batch(250.0, 190.0, 180.0))

            moments.moments shouldHaveSize 1
            val moment = moments.moments.single().shouldBeInstanceOf<TripMoment.DriverArriving>()
            moment.tripId shouldBe trip.id.value
            moment.status shouldBe TripStatus.ACCEPTED
            (moment.remainingM in 175.0..185.0) shouldBe true
            outbox.moments shouldBe moments.moments
        }

    @Test
    fun `the rule state carries over from one batch to the next`() =
        runTest {
            tripIn(TripStatus.ACCEPTED)

            service().onFixesAccepted(batch(190.0))
            moments.moments.shouldBeEmpty()
            service().onFixesAccepted(batch(185.0, startS = 5))

            moments.moments shouldHaveSize 1
        }

    @Test
    fun `TC-4-ARD-03 arrived moves the trip to ARRIVED with cause AUTO and records the status change`() =
        runTest {
            val trip = tripIn(TripStatus.ACCEPTED)

            service().onFixesAccepted(batch(20.0, 10.0, 5.0, 3.0))

            val stored = trips.find(trip.id)!!
            stored.status shouldBe TripStatus.ARRIVED
            stored.version shouldBe trip.version + 1
            val change = outbox.events.single()
            change.to shouldBe TripStatus.ARRIVED
            change.cause shouldBe StatusCause.AUTO
        }

    @Test
    fun `TC-4-ARD-04 a driver who already marked arrived gets no second arrival`() =
        runTest {
            tripIn(TripStatus.ARRIVED)

            service().onFixesAccepted(batch(20.0, 10.0, 5.0))

            outbox.events.shouldBeEmpty()
            moments.moments.shouldBeEmpty()
        }

    @Test
    fun `TC-4-ARV-05 the rules ignore trips that are not ACCEPTED`() =
        runTest {
            val trip = tripIn(TripStatus.IN_PROGRESS)

            service().onFixesAccepted(batch(190.0, 180.0, 20.0, 10.0, 5.0))

            moments.moments.shouldBeEmpty()
            outbox.events.shouldBeEmpty()
            states.states[trip.id] shouldBe null
        }

    @Test
    fun `a batch delivered twice raises nothing the second time`() =
        runTest {
            tripIn(TripStatus.ACCEPTED)
            val batch = batch(190.0, 180.0)

            service().onFixesAccepted(batch)
            service().onFixesAccepted(batch)

            moments.moments shouldHaveSize 1
        }

    @Test
    fun `when the trip changed during auto-arrive, the service fails so the relay retries`() =
        runTest {
            tripIn(TripStatus.ACCEPTED)
            val losesTheRace =
                object : TripRepository by trips {
                    override suspend fun update(trip: Trip) = false
                }

            val error = shouldThrow<DomainException> { service(losesTheRace).onFixesAccepted(batch(20.0, 10.0, 5.0)) }

            error.code shouldBe TripError.CONCURRENT_MODIFICATION
            outbox.events.shouldBeEmpty()
        }

    @Test
    fun `a session without a driver is ignored`() =
        runTest {
            tripIn(TripStatus.ACCEPTED)

            service().onFixesAccepted(batch(190.0, 180.0).copy(driverId = null))

            moments.moments.shouldBeEmpty()
        }
}
