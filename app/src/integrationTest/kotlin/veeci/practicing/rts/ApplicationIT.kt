package veeci.practicing.rts

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase

@Testcontainers(disabledWithoutDocker = true)
class ApplicationIT {
    @Test
    fun `the app starts against a real database and serves requests`() =
        testApplication {
            application { module(TestConfig.load(TestDatabase.env())) }
            client.get("/").status shouldBe HttpStatusCode.OK
        }
}
