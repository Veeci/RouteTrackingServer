package veeci.practicing.rts.platform.http

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.routeSignatures
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.ticket
import veeci.practicing.rts.testing.trip.TripApi
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The admin port (phase 12a): with `HTTP_ADMINPORT` set, the admin plane answers only on its own port, and the public
 * port answers 404 for it. The admin routes come from the routing tree the app serves, not from a list in this test,
 * so a new admin route is checked without a change here.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminPortIT {
    @Test
    fun `TC-12-SRV-01 with an admin port, every admin route answers 404 on the public port and answers on the admin port`() =
        socketApp(env = ENV, adminPort = true) {
            val public = port(baseUrl)
            val admin = port(checkNotNull(adminUrl))

            adminRoutes().forEach { route ->
                withClue("$route on the public port") { public.answer(route) shouldBe HttpStatusCode.NotFound.value }
                withClue("$route on the admin port") { admin.answer(route) shouldBe expectedAnonymous(route) }
            }
        }

    @Test
    fun `TC-12-SRV-01 the admin port keeps CORS, no-store and the security headers, and opens both admin sockets`() =
        socketApp(env = ENV, adminPort = true) {
            val staff = TestTokens.admin()
            adminClient.get("/api/v1/admin/overview") { bearerAuth(staff) }.apply {
                status shouldBe HttpStatusCode.OK
                headers[HttpHeaders.CacheControl] shouldBe "no-store"
                headers["X-Content-Type-Options"] shouldBe "nosniff"
                headers["Referrer-Policy"] shouldBe "no-referrer"
            }
            preflight(adminClient).headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ORIGIN
            preflight(client).apply {
                status shouldBe HttpStatusCode.NotFound
                headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
            }

            val sockets = createClient(checkNotNull(adminUrl)) { install(WebSockets) }
            sockets.webSocket("/ws/v1/admin/fleet?ticket=${ticket(staff, adminClient)}") {
                send(Frame.Text("""{"type":"fleet_subscribe","bbox":[105.80,21.00,105.81,21.01]}"""))
                withTimeout(5.seconds) { (incoming.receive() as Frame.Text).readText() } shouldContain "\"fleet_snapshot\""
            }
            sockets.webSocket("/ws/v1/admin/trip?ticket=${ticket(staff, adminClient)}") {
                send(Frame.Text("""{"type":"subscribe","tripId":"${UUID.randomUUID()}"}"""))
                withTimeout(5.seconds) { (incoming.receive() as Frame.Text).readText() } shouldContain "TRIP_NOT_FOUND"
            }
        }

    @Test
    fun `TC-12-SRV-01 health, metrics, dev tokens and the trip routes answer on both ports`() =
        socketApp(env = ENV, adminPort = true) {
            val trip = TripApi(client).created(TestTokens.guest()).id
            listOf(client, adminClient).forEach { http ->
                http.get("/health/live").status shouldBe HttpStatusCode.OK
                http.get("/metrics").status shouldBe HttpStatusCode.OK
                http.get("/api/v1/trips/$trip") { bearerAuth(TestTokens.operator()) }.status shouldBe HttpStatusCode.OK
                http
                    .post("/api/v1/dev/tokens") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"role":"OPERATOR","displayName":"Admin port"}""")
                    }.status shouldBe HttpStatusCode.OK
            }
        }

    @Test
    fun `TC-12-SRV-02 without an admin port, every admin route answers on the one port, as before`() =
        socketApp(env = ENV) {
            adminUrl.shouldBeNull()
            val single = port(baseUrl)

            adminRoutes().forEach { route -> withClue(route) { single.answer(route) shouldBe expectedAnonymous(route) } }
            client.get("/api/v1/admin/overview") { bearerAuth(TestTokens.operator()) }.status shouldBe HttpStatusCode.OK
            preflight(client).headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ORIGIN
        }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** One port as a client sees it: plain requests and WebSocket upgrades. */
    private class Port(
        val http: HttpClient,
        val sockets: HttpClient,
    )

    private fun SocketTestApp.port(url: String) = Port(createClient(url), createClient(url) { install(WebSockets) })

    /**
     * Every admin route the app serves, REST and sockets. The known ones must be among them, so that a filter that
     * matches nothing cannot pass.
     */
    private fun SocketTestApp.adminRoutes(): Set<String> {
        val routes =
            application
                .routeSignatures()
                .filter { it.substringAfter(" ").startsWith(ADMIN_REST) || it.substringAfter(" ").startsWith(ADMIN_SOCKETS) }
                .toSet()
        routes shouldContainAll
            listOf(
                "GET /ws/v1/admin/fleet",
                "GET /ws/v1/admin/trip",
                "POST /api/v1/admin/ws-tickets",
                "GET /api/v1/admin/overview",
                "OPTIONS /api/v1/admin/{...}",
            )
        return routes
    }

    /**
     * What an anonymous caller gets from a route that exists: 401, because every admin route needs a token or a ticket,
     * except the preflight handler, which answers 204.
     */
    private fun expectedAnonymous(route: String): Int =
        if (route.startsWith("OPTIONS ")) HttpStatusCode.NoContent.value else HttpStatusCode.Unauthorized.value

    /** The status [route] answers an anonymous caller with. Path parameters get a random UUID. */
    private suspend fun Port.answer(route: String): Int {
        val (method, template) = route.split(" ")
        val path = template.replace(PARAMETER, UUID.randomUUID().toString())
        return if (path.startsWith(
                ADMIN_SOCKETS,
            )
        ) {
            upgrade(path)
        } else {
            http.request(path) { this.method = HttpMethod.parse(method) }.status.value
        }
    }

    /** The status of a WebSocket upgrade without a ticket: 101 if a socket opened, else what the server answered. */
    private suspend fun Port.upgrade(path: String): Int {
        val failure = runCatching { sockets.webSocket(path) {} }.exceptionOrNull() ?: return HttpStatusCode.SwitchingProtocols.value
        val status = HANDSHAKE_STATUS.find(failure.message.orEmpty()) ?: throw failure
        return status.groupValues[1].toInt()
    }

    /** A browser's CORS preflight for the overview from the dashboard origin. */
    private suspend fun preflight(http: HttpClient) =
        http.options("/api/v1/admin/overview") {
            header(HttpHeaders.Origin, ORIGIN)
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }

    private companion object {
        const val ORIGIN = "https://admin.example.test"
        const val ADMIN_REST = "/api/v1/admin"
        const val ADMIN_SOCKETS = "/ws/v1/admin"

        // The route walk makes about 60 requests; the matrix tests raise the limit for the same reason.
        val ENV = mapOf("ADMIN_ORIGINS" to ORIGIN, "HTTP_RATELIMITPERMINUTE" to "10000")
        val PARAMETER = Regex("""\{[^}]*\}""")

        /** The Ktor client's message when the server answers the upgrade with another status than 101. */
        val HANDSHAKE_STATUS = Regex("""but was (\d{3})""")
    }
}
