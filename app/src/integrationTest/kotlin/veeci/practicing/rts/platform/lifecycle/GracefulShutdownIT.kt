package veeci.practicing.rts.platform.lifecycle

import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.events.OutboxRelay
import veeci.practicing.rts.platform.ws.WsSessionRegistry
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp

/** Raises the event a real stop (SIGTERM) starts with, then checks what clients observe. */
@Testcontainers(disabledWithoutDocker = true)
class GracefulShutdownIT {
    @Test
    fun `TC-1-SHD-01 stopping says goodbye to open sockets with 1001`() =
        socketApp {
            createClient { install(WebSockets) }.webSocket("/ws/v1/echo") {
                send(Frame.Text("{}"))
                incoming.receive() // the session is established

                beginShutdown()

                closeReason.await()?.code shouldBe CloseReason.Codes.GOING_AWAY.code
            }
        }

    @Test
    fun `stopping turns readiness DOWN so load balancers stop routing here`() =
        socketApp {
            client.get("/health/ready").status shouldBe HttpStatusCode.OK

            beginShutdown()

            val ready = client.get("/health/ready")
            ready.status shouldBe HttpStatusCode.ServiceUnavailable
            ready.shouldMatchContract()
        }

    @Test
    fun `stopping ends the relay and every socket before the connection pool closes`() =
        socketApp {
            createClient { install(WebSockets) }.webSocket("/ws/v1/echo") {
                send(Frame.Text("{}"))
                incoming.receive() // the session is established
                val relay = application.get<OutboxRelay>()
                val sockets = application.get<WsSessionRegistry>()
                relay.isRunning shouldBe true
                sockets.size shouldBe 1

                beginShutdown()

                // Koin closes the pool at ApplicationStopping, which comes after this event: nothing may be left.
                relay.isRunning shouldBe false
                sockets.size shouldBe 0
            }
        }

    private fun SocketTestApp.beginShutdown() = application.monitor.raise(ApplicationStopPreparing, application.environment)
}
