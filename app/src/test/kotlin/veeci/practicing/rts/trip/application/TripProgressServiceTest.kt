package veeci.practicing.rts.trip.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.shared.geo.PolylineCodec
import veeci.practicing.rts.testing.geo.offset
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.testing.trip.InMemoryTripRepository
import veeci.practicing.rts.testing.trip.InMemoryTripRouteRepository
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.DESTINATION
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.trip.adapter.out.memory.InMemoryProgressStateStore
import veeci.practicing.rts.trip.application.port.out.DriverPosition
import veeci.practicing.rts.trip.application.port.out.ProgressBasis
import veeci.practicing.rts.trip.application.port.out.ProgressPublisher
import veeci.practicing.rts.trip.application.port.out.ProgressTarget
import veeci.practicing.rts.trip.application.port.out.TripProgress
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripAction
import veeci.practicing.rts.trip.domain.TripRoute
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class TripProgressServiceTest {
    private val trips = InMemoryTripRepository()
    private val states = InMemoryProgressStateStore()
    private val published = mutableListOf<Any>()
    private val publisher =
        object : ProgressPublisher {
            override fun publish(
                position: DriverPosition,
                progress: TripProgress,
            ) {
                published += position
                published += progress
            }
        }
    private val routes = InMemoryTripRouteRepository()
    private val service = TripProgressService(trips, routes, states, publisher, DirectTransactionRunner)
    private val driver = TripSamples.driver()

    private suspend fun tripIn(status: TripStatus) = TripSamples.inStatus(status, driver = driver).also { trips.insert(it) }

    /** A position [metersNorth] of [from], [s] seconds after T0. One degree of latitude is about 111 195 m. */
    private fun position(
        metersNorth: Double,
        s: Int,
        speedMps: Double? = null,
        from: GeoPoint = PICKUP,
    ) = FixesAccepted.Position(
        GeoPoint(from.lat - metersNorth / 111_195.0, from.lng),
        T0 + s.seconds,
        speedMps,
        bearingDeg = 0.0,
        cumulativeM = 0.0,
    )

    private fun batch(vararg positions: FixesAccepted.Position) =
        FixesAccepted(UUID.randomUUID(), "phone-1", driver.userId.value, seq = 1, positions = positions.toList())

    private fun progress() = published.filterIsInstance<TripProgress>()

    @Test
    fun `TC-3-PRG-01 before the ride the target is the pickup, during the ride the destination`() =
        runTest {
            val accepted = tripIn(TripStatus.ACCEPTED)
            service.onFixesAccepted(batch(position(500.0, 1, speedMps = 10.0)))
            progress().single().target shouldBe ProgressTarget.PICKUP
            progress().single().remainingM shouldBe (500.0 plusOrMinus 1.0)

            trips.update(accepted.also { it.perform(TripAction.ARRIVE, driver, T0) })
            val arrived = trips.find(accepted.id)!!.also { it.perform(TripAction.START, driver, T0) }
            trips.update(arrived)
            published.clear()
            service.onFixesAccepted(batch(position(0.0, 2, speedMps = 10.0)))

            progress().single().target shouldBe ProgressTarget.DESTINATION
            progress().single().remainingM shouldBe (Haversine.distance(PICKUP, DESTINATION).value plusOrMinus 1.0)
        }

    @Test
    fun `TC-3-PRG-02 at a steady 10 m per s, 1000 m from the pickup, the ETA is 100 s`() =
        runTest {
            tripIn(TripStatus.ACCEPTED)

            service.onFixesAccepted(batch(*(0..9).map { position(1_090.0 - it * 10, it, speedMps = 10.0) }.toTypedArray()))

            progress().single().remainingM shouldBe (1_000.0 plusOrMinus 1.0)
            progress().single().etaS shouldBe (100.0 plusOrMinus 0.5)
        }

    @Test
    fun `TC-3-PRG-03 a driver standing still gets an ETA from the minimum speed, never a division by zero`() =
        runTest {
            tripIn(TripStatus.ACCEPTED)

            service.onFixesAccepted(batch(position(400.0, 1, speedMps = 0.0), position(400.0, 2, speedMps = 0.0)))

            progress().single().etaS shouldBe (200.0 plusOrMinus 0.5) // 400 m at 2 m/s
        }

    @Test
    fun `TC-3-PRG-04 a driver without an active trip publishes nothing`() =
        runTest {
            tripIn(TripStatus.REQUESTED)

            service.onFixesAccepted(batch(position(100.0, 1, speedMps = 10.0)))

            published.shouldBeEmpty()
        }

    @Test
    fun `TC-3-PRG-05 the first sample starts the average, later samples count with alpha 0_3`() =
        runTest {
            val trip = tripIn(TripStatus.ACCEPTED)

            service.onFixesAccepted(batch(position(300.0, 1, speedMps = 10.0)))
            states.get(trip.id)!!.speedMps shouldBe 10.0

            service.onFixesAccepted(batch(position(280.0, 2, speedMps = 20.0)))
            states.get(trip.id)!!.speedMps shouldBe (13.0 plusOrMinus 1e-9) // 0.3 * 20 + 0.7 * 10
        }

    @Test
    fun `without a measured speed, the speed between two positions is used`() =
        runTest {
            val trip = tripIn(TripStatus.ACCEPTED)

            service.onFixesAccepted(batch(position(300.0, 0), position(250.0, 5)))

            states.get(trip.id)!!.speedMps shouldBe (10.0 plusOrMinus 0.1) // 50 m in 5 s
        }

    @Test
    fun `TC-3-PRG-06 the position is published before the progress of the same fix`() =
        runTest {
            tripIn(TripStatus.ACCEPTED)

            service.onFixesAccepted(batch(position(100.0, 1, speedMps = 10.0)))

            published[0].shouldBeInstanceOf<DriverPosition>()
            published[1].shouldBeInstanceOf<TripProgress>()
        }

    @Test
    fun `a batch delivered again, or older than the last one, publishes nothing`() =
        runTest {
            tripIn(TripStatus.ACCEPTED)
            val newer = batch(position(100.0, 10, speedMps = 10.0))
            service.onFixesAccepted(newer)
            published.clear()

            service.onFixesAccepted(newer)
            service.onFixesAccepted(batch(position(200.0, 5, speedMps = 10.0)))

            published.shouldBeEmpty()
        }

    @Test
    fun `a finished trip's state is forgotten`() =
        runTest {
            val trip = tripIn(TripStatus.ACCEPTED)
            service.onFixesAccepted(batch(position(100.0, 1, speedMps = 10.0)))

            service.forget(trip.id)

            states.size shouldBe 0
        }

    /**
     * A TRIP route for [trip]: 1000 m from the pickup due south, planned at 10 m/s. Version [version] goes
     * [eastM] metres east first, so two versions differ.
     */
    private suspend fun routeFor(
        trip: Trip,
        version: Int = 1,
        eastM: Double = 0.0,
    ): TripRoute {
        val points = listOf(PICKUP, PICKUP.offset(eastM = eastM), PICKUP.offset(northM = -1_000.0, eastM = eastM))
        val lengthM = 1_000.0 + eastM
        return TripRoute(trip.id, RouteKind.TRIP, version, PolylineCodec.encode(points), lengthM, lengthM / 10.0, T0)
            .also { routes.insert(it) }
    }

    @Test
    fun `during the ride the distance left is measured along the trip route`() =
        runTest {
            val trip = tripIn(TripStatus.IN_PROGRESS)
            routeFor(trip)

            service.onFixesAccepted(batch(position(400.0, 1, speedMps = 10.0)))

            val progress = progress().single()
            progress.basis shouldBe ProgressBasis.ROUTE
            progress.routeVersion shouldBe 1
            progress.target shouldBe ProgressTarget.DESTINATION
            progress.remainingM shouldBe (600.0 plusOrMinus 1.0)
            progress.etaS shouldBe (60.0 plusOrMinus 0.5) // at the planned 10 m/s, k = 1
        }

    @Test
    fun `TC-7-ETA-02 in the service a driver at half the planned speed gets twice the planned time`() =
        runTest {
            val trip = tripIn(TripStatus.IN_PROGRESS)
            routeFor(trip)

            service.onFixesAccepted(batch(position(400.0, 1, speedMps = 5.0)))

            progress().single().etaS shouldBe (120.0 plusOrMinus 0.5)
        }

    @Test
    fun `TC-7-ETA-04 without a route for the stage the progress falls back to the straight line`() =
        runTest {
            val trip = tripIn(TripStatus.ACCEPTED)
            routeFor(trip) // a TRIP route, but before the ride the driver follows an APPROACH route, and there is none

            service.onFixesAccepted(batch(position(500.0, 1, speedMps = 10.0)))

            progress().single().basis shouldBe ProgressBasis.STRAIGHT_LINE
            progress().single().routeVersion shouldBe null
            states.get(trip.id)!!.route shouldBe null
        }

    @Test
    fun `the matcher's place on the route carries over to the next batch`() =
        runTest {
            val trip = tripIn(TripStatus.IN_PROGRESS)
            routeFor(trip)
            service.onFixesAccepted(batch(position(300.0, 1, speedMps = 10.0)))
            val decoded = states.get(trip.id)!!.route!!.line

            service.onFixesAccepted(batch(position(290.0, 2, speedMps = 10.0))) // 10 m back: GPS jitter

            states.get(trip.id)!!.route!!.line shouldBe decoded // same version: not decoded again
            progress().last().remainingM shouldBe (700.0 plusOrMinus 1.0)
        }

    @Test
    fun `a newer route version replaces the old one and matching starts again on it`() =
        runTest {
            val trip = tripIn(TripStatus.IN_PROGRESS)
            routeFor(trip)
            service.onFixesAccepted(batch(position(300.0, 1, speedMps = 10.0)))

            routeFor(trip, version = 2, eastM = 50.0)
            service.onFixesAccepted(batch(position(300.0, 2, speedMps = 10.0, from = PICKUP.offset(eastM = 50.0))))

            progress().last().routeVersion shouldBe 2
            progress().last().remainingM shouldBe (700.0 plusOrMinus 1.0) // 350 m along a 1050 m route
        }
}
