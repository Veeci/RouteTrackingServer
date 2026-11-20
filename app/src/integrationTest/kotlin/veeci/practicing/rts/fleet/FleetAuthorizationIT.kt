package veeci.practicing.rts.fleet

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingNode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.fleet.FleetFixtures.driver
import veeci.practicing.rts.fleet.FleetFixtures.seed
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.plantTicket
import veeci.practicing.rts.testing.routeSignatures
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.testing.ticket
import veeci.practicing.rts.testing.trip.TripApi

/**
 * TC-11-SEC-01 for the fleet routes (phase 11b), the fleet socket's tickets (11c) and the staff trip routes (11d): every
 * route × {anonymous, guest, driver, operator, admin}. Like the trip matrix, an admin route of these slices that the app
 * serves without a row here fails the completeness test. Each cancel call gets a fresh open trip, so an earlier
 * caller's cancel cannot turn the next one into a 409.
 *
 * The admin sockets (the fleet one of 11c and the trip one of 12a) have a matrix of their own: they take a ticket, not a
 * token, and refuse a role by closing the socket, not by a status code.
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
            Row("GET /api/v1/admin/trips", 401, 403, 403, 200, 200),
            Row("POST /api/v1/admin/trips/{id}/cancel", 401, 403, 403, 200, 200),
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
                    val path =
                        template
                            .replace("{driverId}", known.driverId.toString())
                            .replace("{id}", if ("{id}" in template) TripApi(client).created(TestTokens.guest()).id else "")
                    val response =
                        if (method == "POST") {
                            client.post(path) {
                                token?.let(::bearerAuth)
                                contentType(ContentType.Application.Json)
                                setBody("""{"reason":"authorization matrix"}""")
                            }
                        } else {
                            client.get(path) { token?.let(::bearerAuth) }
                        }
                    actual[row.route to caller] = response.status.value
                }
            }
        }
        val served =
            (routing as RoutingNode)
                .routeSignatures()
                .filter { it.isFleetRoute() }
                .toSet()

        return listOf(dynamicTest("every fleet route has a row") { served shouldBe matrix.map { it.route }.toSet() }) +
            matrix.flatMap { row ->
                row.expected.map { (caller, status) ->
                    dynamicTest("${row.route} as $caller is $status") { actual[row.route to caller] shouldBe status }
                }
            }
    }

    // Columns:                                        ANONYMOUS, GUEST,  DRIVER, OPERATOR, ADMIN
    private val socketMatrix =
        listOf(
            SocketRow("/ws/v1/admin/fleet", "401", "4403", "4403", "open", "open"),
            SocketRow("/ws/v1/admin/trip", "401", "4403", "4403", "open", "open"),
        )

    private class SocketRow(
        val path: String,
        vararg outcomes: String,
    ) {
        val expected = Caller.entries.zip(outcomes.toList()).toMap()
    }

    @TestFactory
    fun `TC-11-SEC-01 admin socket authorization matrix`(): List<DynamicTest> {
        val actual = mutableMapOf<Pair<String, Caller>, String>()
        lateinit var served: Set<String>
        socketApp(env = mapOf("HTTP_RATELIMITPERMINUTE" to "10000")) {
            served = application.routeSignatures().filter { " /ws/v1/admin/" in it }.toSet()
            val sockets = createClient { install(WebSockets) }
            socketMatrix.forEach { row ->
                row.expected.keys.forEach { caller -> actual[row.path to caller] = sockets.outcome(row.path, ticketFor(caller)) }
            }
        }

        return listOf(dynamicTest("every admin socket has a row") { served shouldBe socketMatrix.map { "GET ${it.path}" }.toSet() }) +
            socketMatrix.flatMap { row ->
                row.expected.map { (caller, outcome) ->
                    dynamicTest("${row.path} with a ticket of $caller is $outcome") { actual[row.path to caller] shouldBe outcome }
                }
            }
    }

    /** No ticket for ANONYMOUS; a planted one for the roles the API gives none; a real one for staff. */
    private suspend fun SocketTestApp.ticketFor(caller: Caller): String? =
        when (caller) {
            Caller.ANONYMOUS -> null
            Caller.GUEST, Caller.DRIVER -> application.plantTicket(caller.name)
            Caller.OPERATOR -> ticket(TestTokens.operator())
            Caller.ADMIN -> ticket(TestTokens.admin())
        }

    /**
     * What opening [path] with [ticket] gives: the upgrade's status when it is refused (401), the close code when the
     * socket closes at once (4403), or "open" when the socket answers a message of an unknown type with an error, as a
     * running session does.
     */
    private suspend fun HttpClient.outcome(
        path: String,
        ticket: String?,
    ): String {
        var outcome = "no answer"
        val failure =
            runCatching {
                webSocket(ticket?.let { "$path?ticket=$it" } ?: path) {
                    runCatching { send(Frame.Text("""{"type":"not_a_message"}""")) } // fails when the server closed first
                    val frame = incoming.receiveCatching().getOrNull()
                    outcome =
                        if (frame is Frame.Text && WsErrorCodes.UNKNOWN_MESSAGE in frame.readText()) {
                            "open"
                        } else {
                            "${closeReason.await()?.code}"
                        }
                }
            }.exceptionOrNull()
        return failure?.let { HANDSHAKE_STATUS.find(it.message.orEmpty())?.groupValues?.get(1) ?: throw it } ?: outcome
    }

    /**
     * The routes of the fleet slices: the driver list, one driver, the CSV, the overview, the fleet actions, the tickets and
     * the staff trip routes. A driver's own sub-resources (commands, disable, enable) belong to device control and identity,
     * and the trip summary recompute to phase 8, with tests of their own.
     */
    private fun String.isFleetRoute(): Boolean =
        (startsWith("GET /api/v1/admin/drivers") && !contains("{driverId}/")) ||
            startsWith("GET /api/v1/admin/overview") ||
            contains("/api/v1/admin/fleet") ||
            contains("/api/v1/admin/ws-tickets") ||
            (contains("/api/v1/admin/trips") && !endsWith("/summary"))

    private companion object {
        /** The Ktor client's message when the server answers the upgrade with another status than 101. */
        val HANDSHAKE_STATUS = Regex("""but was (\d{3})""")
    }
}
