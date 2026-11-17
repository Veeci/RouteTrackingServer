package veeci.practicing.rts.platform.http

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.testApp

/** CORS on the admin routes and the security headers on every response (phase 11c). */
@Testcontainers(disabledWithoutDocker = true)
class BrowserSecurityIT {
    private val origins = mapOf("ADMIN_ORIGINS" to "$DASHBOARD,http://localhost:5173")

    @Test
    fun `TC-11-SEC-02 a preflight from an allowed origin is allowed, without credentials`() =
        testApp(env = origins) {
            val response = preflight("/api/v1/admin/drivers", DASHBOARD, "authorization,content-type,x-request-id")

            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe DASHBOARD
            response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase() shouldContain "authorization"
            response.headers[HttpHeaders.AccessControlAllowCredentials].shouldBeNull()
        }

    @Test
    fun `TC-11-SEC-02 a preflight from another origin gets no Access-Control-Allow-Origin`() =
        testApp(env = origins) {
            preflight("/api/v1/admin/drivers", "https://evil.example.test")
                .headers[HttpHeaders.AccessControlAllowOrigin]
                .shouldBeNull()
        }

    @Test
    fun `an allowed origin's real request is answered with its origin, an error too`() =
        testApp(env = origins) {
            val ok = client.get("/api/v1/admin/overview") { origin(DASHBOARD) { bearerAuth(TestTokens.operator()) } }
            ok.status shouldBe HttpStatusCode.OK
            ok.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe DASHBOARD

            val refused = client.post("/api/v1/admin/ws-tickets") { origin(DASHBOARD) {} }
            refused.status shouldBe HttpStatusCode.Unauthorized
            refused.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe DASHBOARD
        }

    @Test
    fun `CORS covers the admin routes only, and is off without ADMIN_ORIGINS`() {
        testApp(env = origins) {
            preflight("/api/v1/trips", DASHBOARD).headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
        }
        testApp {
            preflight("/api/v1/admin/drivers", DASHBOARD).headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
        }
    }

    @Test
    fun `TC-11-SEC-04 every response says nosniff and no-referrer, admin responses also no-store`() =
        testApp {
            val health = client.get("/health/live")
            health.headers["X-Content-Type-Options"] shouldBe "nosniff"
            health.headers["Referrer-Policy"] shouldBe "no-referrer"
            health.headers[HttpHeaders.CacheControl].shouldBeNull()

            val admin = client.get("/api/v1/admin/overview") { bearerAuth(TestTokens.operator()) }
            admin.status shouldBe HttpStatusCode.OK
            admin.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            admin.headers["X-Content-Type-Options"] shouldBe "nosniff"

            val refused = client.get("/api/v1/admin/overview")
            refused.status shouldBe HttpStatusCode.Unauthorized
            refused.headers[HttpHeaders.CacheControl] shouldBe "no-store"
        }

    private suspend fun ApplicationTestBuilder.preflight(
        path: String,
        origin: String,
        headers: String = "authorization",
    ): HttpResponse =
        client.options(path) {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, "GET")
            header(HttpHeaders.AccessControlRequestHeaders, headers)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.origin(
        origin: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ) {
        header(HttpHeaders.Origin, origin)
        block()
    }

    private companion object {
        const val DASHBOARD = "https://admin.example.test"
    }
}
