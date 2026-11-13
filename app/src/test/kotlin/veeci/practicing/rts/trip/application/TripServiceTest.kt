package veeci.practicing.rts.trip.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.routing.application.api.PlannedRoute
import veeci.practicing.rts.routing.application.api.RouteMetric
import veeci.practicing.rts.routing.application.api.RoutePlanner
import veeci.practicing.rts.routing.application.api.RoutingError
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.shared.geo.PolylineCodec
import veeci.practicing.rts.testing.routing.TestRouting
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.testing.trip.InMemoryTripEventOutbox
import veeci.practicing.rts.testing.trip.InMemoryTripRepository
import veeci.practicing.rts.testing.trip.InMemoryTripRouteRepository
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.DESTINATION
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripAction
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.toJavaInstant

class TripServiceTest {
    private val trips = InMemoryTripRepository()
    private val outbox = InMemoryTripEventOutbox()
    private val routes = InMemoryTripRouteRepository()
    private val service = service(trips)
    private val guest = TripSamples.guest()
    private val driver = TripSamples.driver()

    private fun service(
        repository: TripRepository,
        planner: RoutePlanner = TestRouting.planner(),
    ) = TripService(repository, outbox, DirectTransactionRunner, Clock.fixed(T0.toJavaInstant(), ZoneOffset.UTC), planner, routes)

    private suspend fun requested() = service.request(RequestTrip(PICKUP, DESTINATION), guest)

    private suspend fun act(
        trip: TripId,
        action: TripAction,
        actor: veeci.practicing.rts.shared.Actor,
        expectedVersion: Long? = null,
    ) = service.act(ActOnTrip(trip, action, expectedVersion), actor)

    @Test
    fun `a guest requests a trip, which is stored and announced with version 1`() =
        runTest {
            val trip = requested()

            trip.status shouldBe TripStatus.REQUESTED
            trip.version shouldBe 1
            trips.find(TripId(trip.id)).shouldNotBeNull()
            outbox.events.map { it.to } shouldBe listOf(TripStatus.REQUESTED)
        }

    @Test
    fun `times are kept to the microsecond, like the database, also with a nanosecond clock`() =
        runTest {
            val nanos = Clock.fixed(java.time.Instant.parse("2026-10-07T09:44:48.607025568Z"), ZoneOffset.UTC)
            val service = TripService(trips, outbox, DirectTransactionRunner, nanos, TestRouting.planner(), routes)

            val trip = service.request(RequestTrip(PICKUP, DESTINATION), guest)

            trip.createdAt.toString() shouldBe "2026-10-07T09:44:48.607025Z"
        }

    @Test
    fun `TC-3-UC-01 a driver may not request a trip`() =
        runTest {
            shouldThrow<DomainException> { service.request(RequestTrip(PICKUP, DESTINATION), driver) }.code shouldBe
                TripError.TRIP_ACTION_FORBIDDEN
        }

    @Test
    fun `each change raises the version by 1 and is announced`() =
        runTest {
            val id = TripId(requested().id)

            val accepted = act(id, TripAction.ACCEPT, driver)
            val arrived = act(id, TripAction.ARRIVE, driver)

            accepted.version shouldBe 2
            arrived.version shouldBe 3
            arrived.driverId shouldBe driver.userId.value
            outbox.events.map { it.to } shouldBe listOf(TripStatus.REQUESTED, TripStatus.ACCEPTED, TripStatus.ARRIVED)
        }

    @Test
    fun `TC-3-UC-02 a save that loses the race is a concurrent modification`() =
        runTest {
            val id = TripId(requested().id)
            val racing =
                object : TripRepository by trips {
                    override suspend fun update(trip: Trip) = false // someone else saved first
                }

            shouldThrow<DomainException> { service(racing).act(ActOnTrip(id, TripAction.ACCEPT), driver) }.code shouldBe
                TripError.CONCURRENT_MODIFICATION
        }

    @Test
    fun `TC-3-UC-03 a driver with an active trip may not accept another`() =
        runTest {
            act(TripId(requested().id), TripAction.ACCEPT, driver)
            val second = TripId(requested().id)

            shouldThrow<DomainException> { act(second, TripAction.ACCEPT, driver) }.code shouldBe TripError.DRIVER_ALREADY_ON_TRIP
            trips.find(second)!!.status shouldBe TripStatus.REQUESTED
        }

    @Test
    fun `a driver who finished a trip may accept the next one`() =
        runTest {
            val first = TripId(requested().id)
            listOf(TripAction.ACCEPT, TripAction.ARRIVE, TripAction.START, TripAction.COMPLETE).forEach { act(first, it, driver) }

            act(TripId(requested().id), TripAction.ACCEPT, driver).status shouldBe TripStatus.ACCEPTED
        }

    @Test
    fun `TC-3-UC-04 another guest gets not found, the same as for a trip that does not exist`() =
        runTest {
            val id = TripId(requested().id)

            shouldThrow<DomainException> { service.get(id, TripSamples.guest()) }.code shouldBe TripError.TRIP_NOT_FOUND
            shouldThrow<DomainException> { act(id, TripAction.CANCEL, TripSamples.guest()) }.code shouldBe TripError.TRIP_NOT_FOUND
            shouldThrow<DomainException> { service.get(TripId(java.util.UUID.randomUUID()), guest) }.code shouldBe TripError.TRIP_NOT_FOUND
        }

    @Test
    fun `TC-3-UC-05 the trip query gives the guest a snapshot and hides the trip from others`() =
        runTest {
            val trip = requested()

            service.snapshotFor(trip.id, guest) shouldBe trip
            shouldThrow<DomainException> { service.snapshotFor(trip.id, TripSamples.guest()) }.code shouldBe TripError.TRIP_NOT_FOUND
        }

    @Test
    fun `a driver who lost the race for a trip hears that it is taken, not that it does not exist`() =
        runTest {
            val id = TripId(requested().id)
            act(id, TripAction.ACCEPT, driver)

            shouldThrow<DomainException> { act(id, TripAction.ACCEPT, TripSamples.driver()) }.code shouldBe TripError.ILLEGAL_TRANSITION
        }

    @Test
    fun `If-Match with the current version succeeds, with an older one it fails and changes nothing`() =
        runTest {
            val id = TripId(requested().id)

            shouldThrow<DomainException> { act(id, TripAction.ACCEPT, driver, expectedVersion = 7) }.code shouldBe
                TripError.VERSION_MISMATCH
            trips.find(id)!!.status shouldBe TripStatus.REQUESTED
            act(id, TripAction.ACCEPT, driver, expectedVersion = 1).version shouldBe 2
        }

    @Test
    fun `drivers page through open trips oldest first, and guests may not list them`() =
        runTest {
            val ids = (1..5).map { requested().id }
            act(TripId(ids[1]), TripAction.ACCEPT, driver) // no longer open

            val first = service.listOpen(driver, after = null, limit = 2)
            val second = service.listOpen(driver, after = first.next, limit = 2)

            val open = ids - ids[1]
            (first.items + second.items).map { it.id } shouldBe open.sortedBy { it.toString() } // same createdAt, so id order
            second.next.shouldBeNull()
            shouldThrow<DomainException> { service.listOpen(guest, null, 10) }.code shouldBe TripError.TRIP_ACTION_FORBIDDEN
        }

    @Test
    fun `TC-6-API-04 booking plans the road route, stores it as TRIP version 1 and returns it`() =
        runTest {
            val trip = requested()

            val route = trip.route.shouldNotBeNull()
            val stored = routes.findLatest(TripId(trip.id), RouteKind.TRIP).shouldNotBeNull()
            stored.version shouldBe 1
            stored.polyline shouldBe route.polyline
            stored.distanceM shouldBe route.distanceM
            val points = PolylineCodec.decode(route.polyline)
            points.first() shouldBe PICKUP // both sample points have 4 decimals, so precision 5 keeps them exactly
            points.last() shouldBe DESTINATION
            route.distanceM shouldBeGreaterThan Haversine.distance(PICKUP, DESTINATION).value // roads are never shorter
            service.get(TripId(trip.id), guest).route shouldBe route
        }

    @Test
    fun `TC-6-API-04 an unroutable booking fails with the routing error and stores nothing`() =
        runTest {
            val inHanoi = GeoPoint(21.0285, 105.8542) // outside the test map, which covers part of Ho Chi Minh City

            shouldThrow<DomainException> { service.request(RequestTrip(inHanoi, DESTINATION), guest) }.code shouldBe
                RoutingError.OUTSIDE_COVERAGE

            trips.findByStatus(TripStatus.REQUESTED, after = null, limit = 10).shouldBeEmpty()
            routes.routes.shouldBeEmpty()
            outbox.events.shouldBeEmpty()
        }

    @Test
    fun `a driver's booking is refused before any route is planned`() =
        runTest {
            val mustNotPlan =
                object : RoutePlanner {
                    override suspend fun plan(
                        from: GeoPoint,
                        to: GeoPoint,
                        metric: RouteMetric,
                    ): PlannedRoute = error("planned a route for a caller who may not book")
                }

            shouldThrow<DomainException> { service(trips, mustNotPlan).request(RequestTrip(PICKUP, DESTINATION), driver) }.code shouldBe
                TripError.TRIP_ACTION_FORBIDDEN
        }

    @Test
    fun `listed trips carry no route, so a page costs no extra query per trip`() =
        runTest {
            requested()

            service
                .listOpen(driver, after = null, limit = 10)
                .items
                .single()
                .route
                .shouldBeNull()
        }
}
