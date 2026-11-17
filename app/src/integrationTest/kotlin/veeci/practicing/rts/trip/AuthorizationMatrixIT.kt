package veeci.practicing.rts.trip

import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.trip.AuthorizationMatrixIT.Caller.ADMIN
import veeci.practicing.rts.trip.AuthorizationMatrixIT.Caller.ANONYMOUS
import veeci.practicing.rts.trip.AuthorizationMatrixIT.Caller.ASSIGNED_DRIVER
import veeci.practicing.rts.trip.AuthorizationMatrixIT.Caller.OPERATOR
import veeci.practicing.rts.trip.AuthorizationMatrixIT.Caller.OTHER_DRIVER
import veeci.practicing.rts.trip.AuthorizationMatrixIT.Caller.OTHER_GUEST
import veeci.practicing.rts.trip.AuthorizationMatrixIT.Caller.OWNER_GUEST

/**
 * TC-3-SEC-01: who may call which trip route. The matrix is data: one row per route, one expected status per kind
 * of caller. Each cell gets a fresh trip and fresh users and becomes its own test. A route that the app serves but
 * the matrix does not list fails the completeness test, so a new route cannot ship without its row.
 */
@Testcontainers(disabledWithoutDocker = true)
class AuthorizationMatrixIT {
    enum class Caller { ANONYMOUS, OWNER_GUEST, OTHER_GUEST, ASSIGNED_DRIVER, OTHER_DRIVER, ADMIN, OPERATOR }

    /** A route, the stage its trip is prepared in (the actions the assigned driver runs first), and the expected statuses. */
    private class Row(
        val route: String,
        val setUp: List<String>,
        val expected: Map<Caller, Int>,
    )

    private fun row(
        route: String,
        setUp: List<String>,
        vararg statuses: Int,
    ) = Row(route, setUp, Caller.entries.zip(statuses.toList() + statuses.last()).toMap()) // OPERATOR: as ADMIN

    // Columns:                                          ANONYMOUS, OWNER_GUEST, OTHER_GUEST, ASSIGNED_DRIVER, OTHER_DRIVER, ADMIN
    private val matrix =
        listOf(
            row("POST /api/v1/trips", emptyList(), 401, 201, 201, 403, 403, 403),
            row("GET /api/v1/trips", emptyList(), 401, 403, 403, 200, 200, 403),
            row("GET /api/v1/trips/{id}", listOf("accept"), 401, 200, 404, 200, 404, 200),
            // An open trip: every driver may see and accept it; ASSIGNED_DRIVER is the one who accepts.
            row("POST /api/v1/trips/{id}/accept", emptyList(), 401, 403, 404, 200, 200, 403),
            row("POST /api/v1/trips/{id}/arrive", listOf("accept"), 401, 403, 404, 200, 404, 403),
            row("POST /api/v1/trips/{id}/start", listOf("accept", "arrive"), 401, 403, 404, 200, 404, 403),
            row("POST /api/v1/trips/{id}/complete", listOf("accept", "arrive", "start"), 401, 403, 404, 200, 404, 403),
            row("POST /api/v1/trips/{id}/cancel", listOf("accept"), 401, 200, 404, 200, 404, 403),
            // Computed after the completion commits: call() waits until the summary exists.
            row("GET /api/v1/trips/{id}/summary", listOf("accept", "arrive", "start", "complete"), 401, 200, 404, 200, 404, 200),
            row("GET /api/v1/trips/{id}/track", listOf("accept", "arrive", "start", "complete"), 401, 200, 404, 200, 404, 200),
            row("GET /api/v1/trips/{id}/export.gpx", listOf("accept", "arrive", "start", "complete"), 401, 200, 404, 200, 404, 200),
            row("GET /api/v1/trips/{id}/export.geojson", listOf("accept", "arrive", "start", "complete"), 401, 200, 404, 200, 404, 200),
        )

    @TestFactory
    fun `TC-3-SEC-01 authorization matrix`(): List<DynamicTest> {
        val actual = mutableMapOf<Pair<String, Caller>, Int>()
        lateinit var routing: Routing
        // 7 callers × every route: more requests than the per-IP limit allows in a minute.
        testApp(env = mapOf("HTTP_RATELIMITPERMINUTE" to "10000"), routes = { routing = this }) {
            matrix.forEach { row -> row.expected.keys.forEach { caller -> actual[row.route to caller] = call(row, caller).status.value } }
        }
        val served =
            (routing as RoutingNode)
                .getAllRoutes()
                .mapNotNull { it.signature() }
                .filter { "/api/v1/trips" in it }
                .toSet()

        return listOf(dynamicTest("every trip route has a row") { served shouldBe matrix.map { it.route }.toSet() }) +
            matrix.flatMap { row ->
                row.expected.map { (caller, status) ->
                    dynamicTest("${row.route} as $caller is $status") { actual[row.route to caller] shouldBe status }
                }
            }
    }

    /** Prepares a fresh trip and fresh users for one cell, then calls the row's route as [caller]. */
    private suspend fun ApplicationTestBuilder.call(
        row: Row,
        caller: Caller,
    ): HttpResponse {
        val api = TripApi(client)
        val owner = TestTokens.guest()
        val assigned = TestTokens.driver()
        val trip = api.created(owner).id
        api.moved(trip, assigned, *row.setUp.toTypedArray())
        val token =
            when (caller) {
                ANONYMOUS -> null
                OWNER_GUEST -> owner
                OTHER_GUEST -> TestTokens.guest()
                ASSIGNED_DRIVER -> assigned
                OTHER_DRIVER -> TestTokens.driver()
                ADMIN -> TestTokens.admin()
                OPERATOR -> TestTokens.operator()
            }
        val (method, path) = row.route.split(" ")
        if (path.endsWith(SUMMARY)) awaitSummary(trip)
        return when {
            path.endsWith(SUMMARY) -> client.get("${TripApi.TRIPS}/$trip$SUMMARY") { token?.let(::bearerAuth) }
            method == "POST" && path == TripApi.TRIPS -> api.request(token)
            method == "GET" && path == TripApi.TRIPS -> api.listOpen(token)
            method == "GET" -> api.get(trip, token)
            else -> api.act(trip, path.substringAfterLast("/"), token)
        }
    }

    /** Waits until the outbox relay has stored the trip's summary; an admin may always read it. */
    private suspend fun ApplicationTestBuilder.awaitSummary(trip: String) {
        val admin = TestTokens.admin()
        val end = System.currentTimeMillis() + SUMMARY_WAIT_MS
        while (client.get("${TripApi.TRIPS}/$trip$SUMMARY") { bearerAuth(admin) }.status != HttpStatusCode.OK) {
            check(System.currentTimeMillis() < end) { "No summary for trip $trip" }
            delay(POLL_MS)
        }
    }

    private companion object {
        const val SUMMARY = "/summary"
        const val SUMMARY_WAIT_MS = 10_000L
        const val POLL_MS = 50L
    }

    /** `GET /api/v1/trips/{id}` for a route that ends in an HTTP method selector; null for other nodes. */
    private fun RoutingNode.signature(): String? {
        val text = toString()
        val method = Regex("""\(method:(\w+)\)""").find(text)?.groupValues?.get(1) ?: return null
        val path = text.replace(Regex("""/?\([^)]*\)"""), "").trimEnd('/')
        return "$method $path"
    }
}
