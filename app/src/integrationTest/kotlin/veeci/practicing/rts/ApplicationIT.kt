package veeci.practicing.rts

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.testApp

@Testcontainers(disabledWithoutDocker = true)
class ApplicationIT {
    @Test
    fun `the app starts against a real database and serves requests`() =
        testApp {
            client.get("/").status shouldBe HttpStatusCode.OK
        }
}
