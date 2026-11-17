package veeci.practicing.rts.fleet

import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.fleet.FleetFixtures.driver
import veeci.practicing.rts.fleet.FleetFixtures.seed
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.testApp

/**
 * TC-11-SEC-01 for the fleet routes (phase 11b) and the fleet socket's tickets (11c): every route × {anonymous, guest,
 * driver, operator, admin}. Like the trip matrix, a fleet route the app serves without a row here fails the completeness
 * test.
 */
@Testcontainers(disabledWithoutDocker = true)
class FleetAuthorizationIT {
    enum class Caller { ANONYMOUS, GUEST, DRIVER, OPERATOR, ADMIN }

    private class Row(
        val route: String,
        vararg statuses: Int,
    ) {
        val expected = Caller.entries.zip(statuses.toList()).toMap()
    }

    // Columns:                                     ANONYMOUS, GUEST, DRIVER, OPERATOR, ADMIN
    private val matrix =
        listOf(
            Row("GET /api/v1/admin/drivers", 401, 403, 403, 200, 200),
            Row("GET /api/v1/admin/drivers/{driverId}", 401, 403, 403, 200, 200),
            Row("GET /api/v1/admin/overview", 401, 403, 403, 200, 200),
            Row("GET /api/v1/admin/drivers.csv", 401, 403, 403, 403, 200),
            Row("POST /api/v1/admin/fleet/rebuild", 401, 403, 403, 403, 200),
            Row("POST /api/v1/admin/ws-tickets", 401, 403, 403, 200, 200),
        )

    @TestFactory
    fun `TC-11-SEC-01 fleet authorization matrix`(): List<DynamicTest> {
        val actual = mutableMapOf<Pair<String, Caller>, Int>()
        lateinit var routing: Routing
        val known = driver(name = "Known")
        testApp(env = TestDatabase.freshDatabaseEnv() + mapOf("HTTP_RATELIMITPERMINUTE" to "10000"), routes = { routing = this }) {
            application.seed(known)
            matrix.forEach { row ->
                row.expected.keys.forEach { caller ->
                    val token =
                        when (caller) {
                            Caller.ANONYMOUS -> null
                            Caller.GUEST -> TestTokens.guest()
                            Caller.DRIVER -> TestTokens.driver()
                            Caller.OPERATOR -> TestTokens.operator()
                            Caller.ADMIN -> TestTokens.admin()
                        }
                    val (method, template) = row.route.split(" ")
                    val path = template.replace("{driverId}", known.driverId.toString())
                    val response =
                        if (method ==
                            "POST"
                        ) {
                            client.post(path) { token?.let(::bearerAuth) }
                        } else {
                            client.get(path) { token?.let(::bearerAuth) }
                        }
                    actual[row.route to caller] = response.status.value
                }
            }
        }
        val served =
            (routing as RoutingNode)
                .getAllRoutes()
                .mapNotNull { it.signature() }
                .filter { it.isFleetRoute() }
                .toSet()

        return listOf(dynamicTest("every fleet route has a row") { served shouldBe matrix.map { it.route }.toSet() }) +
            matrix.flatMap { row ->
                row.expected.map { (caller, status) ->
                    dynamicTest("${row.route} as $caller is $status") { actual[row.route to caller] shouldBe status }
                }
            }
    }

    /**
     * The routes of the fleet slices: the driver list, one driver, the CSV, the overview, the fleet actions and the tickets. A driver's
     * own sub-resources (commands, disable, enable) belong to device control and identity, with tests of their own.
     */
    private fun String.isFleetRoute(): Boolean =
        (startsWith("GET /api/v1/admin/drivers") && !contains("{driverId}/")) ||
            startsWith("GET /api/v1/admin/overview") ||
            contains("/api/v1/admin/fleet") ||
            contains("/api/v1/admin/ws-tickets")

    /** `GET /api/v1/admin/drivers/{driverId}` for a route that ends in an HTTP method selector; null for other nodes. */
    private fun RoutingNode.signature(): String? {
        val text = toString()
        val method = Regex("""\(method:(\w+)\)""").find(text)?.groupValues?.get(1) ?: return null
        val path = text.replace(Regex("""/?\([^)]*\)"""), "").trimEnd('/')
        return "$method $path"
    }
}
