package veeci.practicing.rts.platform.lifecycle

import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp

/** Raises the event a real stop (SIGTERM) starts with, then checks what clients observe. */
@Testcontainers(disabledWithoutDocker = true)
class GracefulShutdownIT {
    @Test
    fun `TC-1-SHD-01 stopping says goodbye to open sockets with 1001`() =
        testApp {
            createClient { install(WebSockets) }.webSocket("/ws/v1/echo") {
                send(Frame.Text("{}"))
                incoming.receive() // the session is established

                beginShutdown()

                closeReason.await()?.code shouldBe CloseReason.Codes.GOING_AWAY.code
            }
        }

    @Test
    fun `stopping turns readiness DOWN so load balancers stop routing here`() =
        testApp {
            client.get("/health/ready").status shouldBe HttpStatusCode.OK

            beginShutdown()

            val ready = client.get("/health/ready")
            ready.status shouldBe HttpStatusCode.ServiceUnavailable
            ready.shouldMatchContract()
        }

    private fun ApplicationTestBuilder.beginShutdown() = application.monitor.raise(ApplicationStopPreparing, application.environment)
}
