package veeci.practicing.rts

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import veeci.practicing.rts.testing.TestConfig

class ServerTest {
    @Test
    fun `root endpoint responds with 200`() =
        testApplication {
            application { module(TestConfig.load()) }
            client.get("/").status shouldBe HttpStatusCode.OK
        }
}
