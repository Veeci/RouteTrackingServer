package veeci.practicing.rts.routing

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.routing.adapter.inbound.web.RouteResponse
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.routing.domain.search.Metric
import veeci.practicing.rts.routing.domain.search.SearchResult
import veeci.practicing.rts.routing.domain.search.ShortestPath
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.PolylineCodec
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.routing.FixtureGraphSource
import veeci.practicing.rts.testing.routing.GatedGraphSource
import veeci.practicing.rts.testing.routing.TestRouting
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp
import org.koin.dsl.module as koinModule

/**
 * `GET /api/v1/routes` on the test map (saigon_test_grid: a 4 x 17 grid of streets 0.002 degrees apart).
 * Every response is checked against the OpenAPI contract.
 */
@Testcontainers(disabledWithoutDocker = true)
class RoutesApiIT {
    private val token = TestTokens.guest()

    @Test
    fun `TC-6-API-01 a route between two points on the map is 200 with polyline, distance and duration`() =
        testApp {
            val response = routes("from=10.770,106.696&to=10.774,106.698").checked()

            response.status shouldBe HttpStatusCode.OK
            val route = response.route()
            // Two blocks north and one block east, in some order: 663.25 m by haversine, computed outside this code.
            route.distanceM shouldBe (663.25 plusOrMinus 0.01)
            route.metric shouldBe "distance"
            PolylineCodec.decode(route.polyline).let { listOf(it.first(), it.last()) } shouldContainExactly
                listOf(GeoPoint(10.770, 106.696), GeoPoint(10.774, 106.698))
        }

    @Test
    fun `metric=time is accepted and echoed`() =
        testApp {
            routes("from=10.770,106.696&to=10.802,106.696&metric=time").checked().route().metric shouldBe "time"
        }

    @Test
    fun `TC-6-API-02 malformed points and an unknown metric are 400 naming every bad parameter`() =
        testApp {
            val response = routes("from=abc&to=10.77&metric=fast").checked()

            response.status shouldBe HttpStatusCode.BadRequest
            val problem = response.problem()
            problem.code shouldBe "VALIDATION_FAILED"
            problem.errors?.map { it.field } shouldBe listOf("from", "to", "metric")
            routes("to=10.770,106.696").checked().status shouldBe HttpStatusCode.BadRequest // from missing
            routes("from=91,0&to=10.770,106.696").checked().status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `TC-6-API-03 a point far from every road is 422 NO_ROAD_NEARBY, one in another city OUTSIDE_COVERAGE`() =
        testApp {
            val noRoad = routes("from=10.78,106.7055&to=10.770,106.696").checked()
            val hanoi = routes("from=10.770,106.696&to=21.0285,105.8542").checked()

            noRoad.status shouldBe HttpStatusCode.UnprocessableEntity
            noRoad.problem().code shouldBe "NO_ROAD_NEARBY"
            hanoi.status shouldBe HttpStatusCode.UnprocessableEntity
            hanoi.problem().code shouldBe "OUTSIDE_COVERAGE"
        }

    @Test
    fun `the route needs a token`() =
        testApp {
            val response = client.get("/api/v1/routes?from=10.770,106.696&to=10.774,106.698").checked()

            response.status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `before the graph has loaded a route is 503 ROUTING_NOT_READY`() {
        val gated = GatedGraphSource(FixtureGraphSource(TestRouting.CITY_GRAPH))
        testApp(overrides = listOf(TestRouting.module(gated)), awaitRoadGraph = false) {
            val response = routes("from=10.770,106.696&to=10.774,106.698").checked()

            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.problem().code shouldBe "ROUTING_NOT_READY"
            gated.release()
        }
    }

    @Test
    fun `TC-6-PERF-02 planning over its time budget is 503 ROUTING_TIMEOUT`() =
        testApp(
            env = mapOf("ROUTING_PLANTIMEOUT" to "50ms"),
            overrides = listOf(koinModule { single<ShortestPath> { NeverEndingSearch } }),
        ) {
            val response = routes("from=10.770,106.696&to=10.774,106.698").checked()

            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.problem().code shouldBe "ROUTING_TIMEOUT"
        }

    /** A search that only stops when its checkpoint throws, as the planner's timeout makes it do. */
    private object NeverEndingSearch : ShortestPath {
        override fun search(
            graph: RoadGraph,
            from: Int,
            to: Int,
            metric: Metric,
            checkpoint: () -> Unit,
        ): SearchResult? {
            while (true) {
                Thread.sleep(1)
                checkpoint()
            }
        }
    }

    private suspend fun ApplicationTestBuilder.routes(query: String): HttpResponse =
        client.get("/api/v1/routes?$query") { bearerAuth(token) }

    private suspend fun HttpResponse.route(): RouteResponse = Json.decodeFromString(bodyAsText())

    /** Checks the response against the contract and returns it, for chaining. */
    private suspend fun HttpResponse.checked(): HttpResponse = also { it.shouldMatchContract() }
}
