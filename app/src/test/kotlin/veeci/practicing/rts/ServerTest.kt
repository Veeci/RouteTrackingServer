package veeci.practicing.rts

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class ServerTest {
    @Test
    fun `root endpoint responds with 200`() =
        testApplication {
            configure() // loads application.yaml modules
            client.get("/").status shouldBe HttpStatusCode.OK
        }
}
