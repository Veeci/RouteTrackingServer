package veeci.practicing.rts.platform.observability

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Testcontainers(disabledWithoutDocker = true)
class HealthIT {
    @Test
    fun `live answers UP without checking anything`() =
        testApp {
            val response = client.get("/health/live")

            response.status shouldBe HttpStatusCode.OK
            response.report() shouldBe HealthReport(HealthStatus.UP)
        }

    @Test
    fun `TC-1-HLT-02 ready answers UP when the database answers`() =
        testApp {
            val response = client.get("/health/ready")

            response.status shouldBe HttpStatusCode.OK
            response.report() shouldBe HealthReport(HealthStatus.UP, mapOf("db" to HealthStatus.UP))
        }

    @Test
    fun `TC-1-HLT-01 TC-1-HLT-02 a frozen database turns ready DOWN, leaves live UP, and recovers`() {
        // A container of its own: freezing the shared one would break every other test.
        TestDatabase.newContainer().use { db ->
            db.start()
            testApp(env = TestDatabase.envFor(db)) {
                client.get("/health/ready").status shouldBe HttpStatusCode.OK

                db.pause()
                try {
                    client.get("/health/live").status shouldBe HttpStatusCode.OK

                    val started = TimeSource.Monotonic.markNow()
                    val ready = client.get("/health/ready")
                    ready.status shouldBe HttpStatusCode.ServiceUnavailable
                    ready.report().checks["db"] shouldBe HealthStatus.DOWN
                    ready.shouldMatchContract()
                    (started.elapsedNow() < 3.seconds) shouldBe true // answered by the deadline, not Hikari's timeout
                } finally {
                    db.unpause()
                }

                awaitStatus("/health/ready", HttpStatusCode.OK, within = 15.seconds)
            }
        }
    }

    private suspend fun HttpResponse.report(): HealthReport = Json.decodeFromString(bodyAsText())

    private suspend fun ApplicationTestBuilder.awaitStatus(
        path: String,
        expected: HttpStatusCode,
        within: Duration,
    ) {
        val deadline = TimeSource.Monotonic.markNow() + within
        var last = client.get(path).status
        while (last != expected && deadline.hasNotPassedNow()) {
            delay(250.milliseconds)
            last = client.get(path).status
        }
        last shouldBe expected
    }

    private fun PostgreSQLContainer.pause() {
        dockerClient.pauseContainerCmd(containerId).exec()
    }

    private fun PostgreSQLContainer.unpause() {
        dockerClient.unpauseContainerCmd(containerId).exec()
    }
}
