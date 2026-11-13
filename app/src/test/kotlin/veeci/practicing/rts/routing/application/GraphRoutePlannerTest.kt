package veeci.practicing.rts.routing.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.routing.application.api.RouteMetric
import veeci.practicing.rts.routing.application.api.RoutingError
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.routing.domain.search.AStar
import veeci.practicing.rts.routing.domain.search.Metric
import veeci.practicing.rts.routing.domain.search.SearchResult
import veeci.practicing.rts.routing.domain.search.ShortestPath
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.routing.GraphFixtures
import veeci.practicing.rts.testing.routing.TestRouting
import veeci.practicing.rts.testing.trip.TripSamples
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The planner's rules on the hand-written fixtures. Expected lengths were computed outside this code (haversine,
 * R = 6 371 008.8 m) and the tiny_grid path cost comes from that file's comment block.
 */
class GraphRoutePlannerTest {
    private val tinyGrid = TestRouting.planner("tiny_grid")

    @Test
    fun `TC-6-RS-01 the polyline starts at from and ends at to, and the distance includes both snap legs`() =
        runTest {
            val from = GeoPoint(21.0300, 105.8495) // 51.894 m west of node 1
            val to = GeoPoint(21.0280, 105.8525) // 51.895 m east of node 9

            val route = tinyGrid.plan(from, to)

            route.polyline.first() shouldBe from
            route.polyline.last() shouldBe to
            route.polyline shouldContainExactly
                listOf(from) +
                listOf(21.030 to 105.850, 21.030 to 105.851, 21.030 to 105.852, 21.029 to 105.852, 21.028 to 105.852)
                    .map { (lat, lng) -> GeoPoint(lat, lng) } + listOf(to)
            route.distanceM shouldBe (533.757 plusOrMinus 0.01) // 429.968 on the roads + 51.894 + 51.895
            route.metric shouldBe RouteMetric.DISTANCE
        }

    @Test
    fun `the duration adds the snap legs at the service road speed to the search's time`() =
        runTest {
            val route = tinyGrid.plan(GeoPoint(21.0300, 105.8495), GeoPoint(21.0280, 105.8525))

            // All tiny_grid streets are residential (30 km/h); the two legs run at 15 km/h.
            route.durationS shouldBe (429.968 / (30 / 3.6) + 103.789 / (15 / 3.6) plusOrMinus 0.01)
        }

    @Test
    fun `TC-6-RS-02 a point with no road within 300 m is NO_ROAD_NEARBY and the message names it`() =
        runTest {
            val inTheLake = GeoPoint(21.0226, 105.851) // 600 m south of the grid, inside the coverage margin

            val error = shouldThrow<DomainException> { tinyGrid.plan(inTheLake, GeoPoint(21.029, 105.851)) }
            error.code shouldBe RoutingError.NO_ROAD_NEARBY
            error.message shouldContain "`from`"

            shouldThrow<DomainException> { tinyGrid.plan(GeoPoint(21.029, 105.851), inTheLake) }.message shouldContain "`to`"
        }

    @Test
    fun `TC-6-RS-03 a point outside the bounding box plus 1 km is OUTSIDE_COVERAGE, one just inside it is not`() =
        runTest {
            val node5 = GeoPoint(21.029, 105.851)

            shouldThrow<DomainException> { tinyGrid.plan(TripSamples.PICKUP, node5) }.code shouldBe RoutingError.OUTSIDE_COVERAGE
            shouldThrow<DomainException> { tinyGrid.plan(node5, GeoPoint(21.0408, 105.851)) }.code shouldBe
                RoutingError.OUTSIDE_COVERAGE // 1 201 m north of the box
            shouldThrow<DomainException> { tinyGrid.plan(node5, GeoPoint(21.0381, 105.851)) }.code shouldBe
                RoutingError.NO_ROAD_NEARBY // 901 m north: covered, but no road there
        }

    @Test
    fun `TC-6-RS-04 two points that snap to the same node give the straight line between them`() =
        runTest {
            val from = GeoPoint(21.0291, 105.851)
            val to = GeoPoint(21.0289, 105.8511)

            val route = tinyGrid.plan(from, to)

            route.polyline shouldContainExactly listOf(from, to)
            route.distanceM shouldBe (24.542 plusOrMinus 0.001)
        }

    @Test
    fun `a point exactly on a node is not repeated in the polyline`() =
        runTest {
            val route = tinyGrid.plan(GeoPoint(21.030, 105.850), GeoPoint(21.030, 105.852))

            route.polyline shouldContainExactly listOf(GeoPoint(21.030, 105.850), GeoPoint(21.030, 105.851), GeoPoint(21.030, 105.852))
            route.distanceM shouldBe (2 * 103.789 plusOrMinus 0.01)
        }

    @Test
    fun `points on two islands with no road between them are NO_ROUTE_FOUND`() =
        runTest {
            val unpruned = GraphFixtures.load("two_islands", pruneToLargestComponent = false)
            val planner = GraphRoutePlanner(TestRouting.holderWith(unpruned), AStar())

            shouldThrow<DomainException> { planner.plan(GeoPoint(21.0400, 105.8400), GeoPoint(21.0400, 105.8700)) }.code shouldBe
                RoutingError.NO_ROUTE_FOUND
        }

    @Test
    fun `before the graph is loaded the planner answers ROUTING_NOT_READY`() =
        runTest {
            val planner = GraphRoutePlanner(RoadGraphHolder(), AStar())

            shouldThrow<DomainException> { planner.plan(TripSamples.PICKUP, TripSamples.DESTINATION) }.code shouldBe
                RoutingError.ROUTING_NOT_READY
        }

    @Test
    fun `the TIME metric takes the primary road, which is longer but quicker`() =
        runTest {
            val city = TestRouting.planner()
            val from = GeoPoint(10.770, 106.696)
            val to = GeoPoint(10.802, 106.696)

            val shortest = city.plan(from, to, RouteMetric.DISTANCE)
            val quickest = city.plan(from, to, RouteMetric.TIME)

            quickest.metric shouldBe RouteMetric.TIME
            quickest.polyline.any { it.lng == 106.700 } shouldBe true // the primary column
            quickest.distanceM shouldBeGreaterThan shortest.distanceM
            quickest.durationS shouldBeLessThan shortest.durationS
        }

    @Test
    fun `TC-6-PERF-02 a search over its time budget is stopped and answered with ROUTING_TIMEOUT`() =
        runTest {
            val searchEnded = AtomicBoolean(false)
            val endless =
                object : ShortestPath {
                    override fun search(
                        graph: RoadGraph,
                        from: Int,
                        to: Int,
                        metric: Metric,
                        checkpoint: () -> Unit,
                    ): SearchResult? {
                        try {
                            while (true) {
                                Thread.sleep(1)
                                checkpoint()
                            }
                        } finally {
                            searchEnded.set(true)
                        }
                    }
                }
            val planner = TestRouting.planner("tiny_grid", endless, PlannerSettings(timeout = 50.milliseconds))
            val started = TimeSource.Monotonic.markNow()

            shouldThrow<DomainException> { planner.plan(GeoPoint(21.030, 105.850), GeoPoint(21.028, 105.852)) }.code shouldBe
                RoutingError.ROUTING_TIMEOUT

            (started.elapsedNow() < 2.seconds) shouldBe true
            // The CPU work stopped too: the caller was not just released while the search ran on in the background.
            searchEnded.get() shouldBe true
        }
}
