package veeci.practicing.rts.platform.security

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.testApp

/** The staging access gate on the dev token route (phase 1.5, T1b.3). */
@Testcontainers(disabledWithoutDocker = true)
class StagingGateIT {
    private val staging = mapOf("APP_ENV" to "staging", "STAGING_ACCESS_TOKEN" to TOKEN, "DB_MIGRATEONSTART" to "true")

    @Test
    fun `TC-1b-GATE-01 in staging the dev token route needs the staging access token`() =
        testApp(env = staging) {
            val missing = requestToken()
            missing.status shouldBe HttpStatusCode.Unauthorized
            missing.problem().code shouldBe "UNAUTHORIZED"

            val wrong = requestToken { header(STAGING_TOKEN_HEADER, TOKEN.dropLast(1) + "x") }
            wrong.status shouldBe HttpStatusCode.Unauthorized
            wrong.problem().code shouldBe "UNAUTHORIZED"

            val right = requestToken { header(STAGING_TOKEN_HEADER, TOKEN) }
            right.status shouldBe HttpStatusCode.OK
            right.tokenField().shouldNotBeBlank()
        }

    @Test
    fun `TC-1b-GATE-02 in staging the health routes need no token`() =
        testApp(env = staging) {
            client.get("/health/live").status shouldBe HttpStatusCode.OK
            client.get("/health/ready").status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `TC-1b-CFG-01 staging serves no Swagger UI`() =
        testApp(env = staging) {
            client.get("/docs").status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `TC-1b-GATE-03 in test the route works without any staging token`() =
        testApp {
            requestToken().status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `TC-1b-GATE-03 in dev the route works without any staging token`() =
        testApp(env = mapOf("APP_ENV" to "dev")) {
            requestToken().status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `TC-1b-GATE-03 in prod the route does not exist, with or without the token`() =
        testApp(env = mapOf("APP_ENV" to "prod", "STAGING_ACCESS_TOKEN" to TOKEN, "DB_MIGRATEONSTART" to "true")) {
            requestToken().status shouldBe HttpStatusCode.NotFound
            requestToken { header(STAGING_TOKEN_HEADER, TOKEN) }.status shouldBe HttpStatusCode.NotFound
        }

    private suspend fun ApplicationTestBuilder.requestToken(extra: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        client.post("/api/v1/dev/tokens") {
            contentType(ContentType.Application.Json)
            setBody("""{"role":"DRIVER"}""")
            extra()
        }

    private suspend fun HttpResponse.tokenField(): String =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("token")
            .jsonPrimitive.content

    private companion object {
        /** A fixed token for tests only, 48 hex characters like `openssl rand -hex 24`. */
        const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
