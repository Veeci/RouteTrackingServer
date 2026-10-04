package veeci.practicing.rts.contract

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp

@Testcontainers(disabledWithoutDocker = true)
class ApiContractIT {
    @Test
    fun `TC-1-API-01 health and metrics responses match the contract`() =
        testApp {
            client.get("/health/live").shouldMatchContract()
            client.get("/health/ready").shouldMatchContract()
            client.get("/metrics").shouldMatchContract()
        }

    @Test
    fun `a rate-limited response matches the contract's Problem`() =
        testApp(env = mapOf("HTTP_RATELIMITPERMINUTE" to "1")) {
            client.get("/health/live")

            val limited = client.get("/health/live")

            limited.status shouldBe HttpStatusCode.TooManyRequests
            limited.shouldMatchContract()
        }

    @Test
    fun `Swagger UI renders the contract in dev`() =
        testApp(env = mapOf("APP_ENV" to "dev")) {
            client.get("/docs").status shouldBe HttpStatusCode.OK
            client.get("/docs/rts-v1.yaml").bodyAsText() shouldContain "title: RouteTracking Server API"
        }

    @Test
    fun `Swagger UI is not served outside dev`() =
        testApp {
            client.get("/docs").status shouldBe HttpStatusCode.NotFound
        }
}
