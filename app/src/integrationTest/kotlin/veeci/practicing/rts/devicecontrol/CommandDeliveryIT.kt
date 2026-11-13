package veeci.practicing.rts.devicecontrol

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.protocol.CommandAck
import veeci.practicing.rts.protocol.ErrorMessage
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.socketApp
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Commands over the driver socket: live, after welcome, again after a reconnect, and acked. */
@Testcontainers(disabledWithoutDocker = true)
class CommandDeliveryIT {
    private val driverId = TestTokens.newUser()
    private val driver = TestTokens.driver(driverId)
    private val admin = TestTokens.admin()
    private val commandsPath = "/api/v1/admin/drivers/${driverId.value}/commands"

    @Test
    fun `TC-5-WS-01 TC-5-CMD-01 a command sent while the driver is connected arrives live and is marked SENT`() =
        socketApp {
            connectDriver {
                val id = issueFlush()

                val command = receiveServer().shouldBeInstanceOf<Command>()
                command.commandId shouldBe id
                command.kind shouldBe "FLUSH_QUEUE"
                eventually { statusOf(id) shouldBe "SENT" }
            }
        }

    @Test
    fun `TC-5-WS-02 a command issued while the driver is offline arrives right after welcome`() =
        socketApp {
            val id = issueFlush()
            awaitRelay()

            connectDriver { receiveServer().shouldBeInstanceOf<Command>().commandId shouldBe id }
        }

    @Test
    fun `TC-5-WS-03 a command sent but not acked arrives again after a reconnect`() =
        socketApp {
            connectDriver {
                val id = issueFlush()
                receiveServer().shouldBeInstanceOf<Command>().commandId shouldBe id
            } // closes without an ack

            connectDriver { receiveServer().shouldBeInstanceOf<Command>().kind shouldBe "FLUSH_QUEUE" }
        }

    @Test
    fun `TC-5-WS-04 an applied ack shows APPLIED, and the command is not sent again`() =
        socketApp {
            connectDriver {
                val id = issueFlush()
                receiveServer().shouldBeInstanceOf<Command>()
                sendClient(CommandAck(id, CommandAck.APPLIED))
                eventually { statusOf(id) shouldBe "APPLIED" }
            }

            connectDriver {
                sendClient(CommandAck(UUID.randomUUID().toString(), CommandAck.APPLIED)) // nothing pending: next is this error
                receiveServer().shouldBeInstanceOf<ErrorMessage>().code shouldBe "COMMAND_NOT_FOUND"
            }
        }

    @Test
    fun `an ack with a bad result is INVALID_MESSAGE and the session stays open`() =
        socketApp {
            connectDriver {
                sendClient(CommandAck(UUID.randomUUID().toString(), "done"))
                receiveServer().shouldBeInstanceOf<ErrorMessage>().code shouldBe "INVALID_MESSAGE"
                sendClient(CommandAck(UUID.randomUUID().toString(), CommandAck.REJECTED))
                receiveServer().shouldBeInstanceOf<ErrorMessage>().code shouldBe "COMMAND_NOT_FOUND"
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** Opens the driver socket, says hello, and runs [block] after `welcome`. */
    private suspend fun SocketTestApp.connectDriver(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        createClient { install(WebSockets) }.webSocket("/ws/v1/driver", request = { bearerAuth(driver) }) {
            withTimeout(10.seconds) {
                sendClient(Hello(1, "phone-1", UUID.randomUUID().toString(), sdkVersion = "0.9.0"))
                receiveServer().shouldBeInstanceOf<Welcome>()
                block()
            }
        }

    private suspend fun SocketTestApp.issueFlush(): String {
        val response =
            client.post(commandsPath) {
                bearerAuth(admin)
                contentType(ContentType.Application.Json)
                setBody("""{"kind":"FLUSH_QUEUE"}""")
            }
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("commandId")
            .jsonPrimitive.content
    }

    private suspend fun SocketTestApp.statusOf(id: String): String =
        Json
            .parseToJsonElement(client.get(commandsPath) { bearerAuth(admin) }.bodyAsText())
            .jsonObject
            .getValue("items")
            .jsonArray
            .single {
                it.jsonObject
                    .getValue("commandId")
                    .jsonPrimitive.content == id
            }.jsonObject
            .getValue("status")
            .jsonPrimitive.content

    /** The relay delivers CommandIssued every 20 ms in tests; with nobody connected it changes nothing. */
    private suspend fun awaitRelay() = delay(200.milliseconds)

    private suspend fun DefaultClientWebSocketSession.sendClient(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    private suspend fun DefaultClientWebSocketSession.receiveServer(): ServerMessage =
        ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())

    private suspend fun eventually(assertion: suspend () -> Unit) {
        withTimeout(5.seconds) {
            while (runCatching { assertion() }.isFailure) delay(20.milliseconds)
        }
        assertion()
    }
}
