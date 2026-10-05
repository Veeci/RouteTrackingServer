package veeci.practicing.rts.platform.ws

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.testing.testApp

@Testcontainers(disabledWithoutDocker = true)
class WsEchoIT {
    @Test
    fun `TC-1-WS-01 echo sends every JSON object back`() =
        testApp {
            wsClient().webSocket(ECHO) {
                send(Frame.Text("""{"hello":"world"}"""))

                receiveText() shouldBe """{"hello":"world"}"""
            }
        }

    @Test
    fun `TC-1-WS-02 a frame over the size limit closes the session with 1009`() =
        testApp(env = mapOf("WS_MAXFRAMEBYTES" to "1024")) {
            wsClient().webSocket(ECHO) {
                send(Frame.Text("""{"pad":"${"x".repeat(2_000)}"}"""))

                closeReason.await()?.code shouldBe CloseReason.Codes.TOO_BIG.code
            }
        }

    @Test
    fun `TC-1-WS-03 invalid JSON gets an error frame and the session stays open`() =
        testApp {
            wsClient().webSocket(ECHO) {
                send(Frame.Text("not json"))
                receiveError().code shouldBe WsErrorCodes.MALFORMED_MESSAGE

                send(Frame.Text("""{"still":"open"}"""))
                receiveText() shouldBe """{"still":"open"}"""
            }
        }

    @Test
    fun `JSON that is not an accepted message gets INVALID_MESSAGE`() =
        testApp {
            wsClient().webSocket(ECHO) {
                send(Frame.Text("""[1, 2, 3]"""))

                receiveError().code shouldBe WsErrorCodes.INVALID_MESSAGE
            }
        }

    @Test
    fun `messages beyond the per-session rate are dropped with RATE_LIMITED`() =
        testApp(env = mapOf("WS_MESSAGESPERSECOND" to "2")) {
            wsClient().webSocket(ECHO) {
                repeat(3) { send(Frame.Text("""{"n":$it}""")) }

                val replies = List(3) { receiveText() }

                replies.take(2) shouldBe listOf("""{"n":0}""", """{"n":1}""")
                (ProtocolJson.decodeFromString(ServerMessage.serializer(), replies[2]) as ErrorMessage).code shouldBe
                    WsErrorCodes.RATE_LIMITED
            }
        }

    @Test
    fun `TC-1-WS-04 echo is mounted in staging`() =
        testApp(env = mapOf("APP_ENV" to "staging")) {
            wsClient().webSocket(ECHO) {
                send(Frame.Text("{}"))
                receiveText() shouldBe "{}"
            }
        }

    @Test
    fun `TC-1-WS-04 echo is not mounted in prod`() =
        testApp(env = mapOf("APP_ENV" to "prod")) {
            // The same client connects in staging (test above); here the upgrade is refused (404).
            shouldThrow<IllegalStateException> { wsClient().webSocket(ECHO) {} }
        }

    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun DefaultClientWebSocketSession.receiveText(): String = (incoming.receive() as Frame.Text).readText()

    private suspend fun DefaultClientWebSocketSession.receiveError(): ErrorMessage =
        ProtocolJson.decodeFromString(ServerMessage.serializer(), receiveText()) as ErrorMessage

    private companion object {
        const val ECHO = "/ws/v1/echo"
    }
}
