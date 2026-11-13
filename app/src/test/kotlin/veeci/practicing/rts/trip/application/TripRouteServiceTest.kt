package veeci.practicing.rts.trip.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import veeci.practicing.rts.testing.geo.offset
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.testing.trip.InMemoryTripEventOutbox
import veeci.practicing.rts.testing.trip.InMemoryTripMomentRepository
import veeci.practicing.rts.testing.trip.InMemoryTripRepository
import veeci.practicing.rts.testing.trip.InMemoryTripRouteRepository
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.DESTINATION
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.RouteReason
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripRoute
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

class TripRouteServiceTest {
    private val trips = InMemoryTripRepository()
    private val routes = InMemoryTripRouteRepository()
    private val moments = InMemoryTripMomentRepository()
    private val outbox = InMemoryTripEventOutbox()
    private val clock = Clock.fixed(java.time.Instant.parse("2026-10-07T09:01:00Z"), ZoneOffset.UTC)

    /** Plans the straight line at 10 m/s, or fails with [failWith]. */
    private class FakePlanner(
        var failWith: RoutingError? = null,
    ) : RoutePlanner {
        override suspend fun plan(
            from: GeoPoint,
            to: GeoPoint,
            metric: RouteMetric,
        ): PlannedRoute {
            failWith?.let { throw DomainException(it, "no route") }
            val d = Haversine.distance(from, to).value
            return PlannedRoute(listOf(from, to), d, d / 10, metric)
        }
    }

    private val planner = FakePlanner()
    private val service = TripRouteService(trips, routes, moments, outbox, planner, DirectTransactionRunner, clock)
    private val driver = TripSamples.driver()
    private val offTheRoute = PICKUP.offset(northM = 300.0, eastM = 80.0)

    private suspend fun tripIn(status: TripStatus): Trip =
        TripSamples.inStatus(status, driver = driver).also {
            trips.insert(it)
            routes.insert(TripRoute(it.id, RouteKind.TRIP, 1, PolylineCodec.encode(listOf(PICKUP, DESTINATION)), 812.0, 81.2, T0))
        }

    private fun request(
        trip: Trip,
        baseVersion: Int = 1,
        kind: RouteKind = RouteKind.TRIP,
    ) = RerouteRequest(trip.id, kind, baseVersion, offTheRoute, DESTINATION, T0 + 5.seconds)

    @Test
    fun `TC-7-RR-01 a re-route stores the next version from the driver's fix, with the moment and the event`() =
        runTest {
            val trip = tripIn(TripStatus.IN_PROGRESS)

            service.reroute(request(trip)) shouldBe RerouteOutcome.REROUTED

            val stored = routes.findLatest(trip.id, RouteKind.TRIP).shouldNotBeNull()
            stored.version shouldBe 2
            stored.reason shouldBe RouteReason.OFF_ROUTE
            PolylineCodec.decode(stored.polyline).first() shouldBe GeoPoint(10.7752, 106.69873) // the fix, at precision 5
            moments.moments
                .single()
                .shouldBeInstanceOf<TripMoment.OffRoute>()
                .at shouldBe T0 + 5.seconds
            outbox.moments.single().shouldBeInstanceOf<TripMoment.OffRoute>()
            val change = outbox.routeChanges.single()
            change.route shouldBe stored
            change.guestId shouldBe trip.guestId.value
            change.driverId shouldBe driver.userId.value
        }

    @Test
    fun `TC-7-RR-03 when no route can be planned the old one stays and only the moment is stored`() =
        runTest {
            val trip = tripIn(TripStatus.IN_PROGRESS)
            planner.failWith = RoutingError.NO_ROAD_NEARBY

            service.reroute(request(trip)) shouldBe RerouteOutcome.FAILED

            routes.findLatest(trip.id, RouteKind.TRIP)!!.version shouldBe 1
            outbox.moments.single().shouldBeInstanceOf<TripMoment.OffRoute>()
            outbox.routeChanges.shouldBeEmpty()
        }

    @Test
    fun `a request from an older version is dropped, so two re-routes of one version store one route`() =
        runTest {
            val trip = tripIn(TripStatus.IN_PROGRESS)

            service.reroute(request(trip)) shouldBe RerouteOutcome.REROUTED
            service.reroute(request(trip)) shouldBe RerouteOutcome.DROPPED

            routes.routes.map { it.version } shouldBe listOf(1, 2)
            outbox.routeChanges.size shouldBe 1
            outbox.moments.size shouldBe 1
        }

    @Test
    fun `a request for a stage the trip has left is dropped`() =
        runTest {
            val trip = tripIn(TripStatus.COMPLETED)

            service.reroute(request(trip)) shouldBe RerouteOutcome.DROPPED

            outbox.moments.shouldBeEmpty()
            outbox.routeChanges.shouldBeEmpty()
        }
}
