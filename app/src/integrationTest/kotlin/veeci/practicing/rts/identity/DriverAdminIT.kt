package veeci.practicing.rts.identity

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Phase 11a: an admin disables a driver, the driver's socket closes, the audit log shows it. */
@Testcontainers(disabledWithoutDocker = true)
class DriverAdminIT {
    private val driverId = UUID.randomUUID().toString()
    private val admin = TestTokens.admin()

    @Test
    fun `TC-11-API-02 disabling closes the driver's socket with 4403 and refuses hello, enabling lets them back`() =
        socketApp {
            val driver = driverToken()
            val ws = createClient { install(WebSockets) }
            var closed: CloseReason? = null
            ws.webSocket("/ws/v1/driver", request = { bearerAuth(driver) }) {
                hello()
                (receive() is Welcome) shouldBe true
                change("disable", """{"reason":"Reported for unsafe driving"}""").apply {
                    status shouldBe HttpStatusCode.OK
                    shouldMatchContract()
                    bodyAsText() shouldContain "\"disabled\":true"
                }
                closed = withTimeout(5.seconds) { closeReason.await() }
            }
            closed?.code shouldBe WsCloseCodes.FORBIDDEN_ROLE

            connectOnce(ws, driver)?.code shouldBe WsCloseCodes.FORBIDDEN_ROLE // a new hello is refused

            change("enable", """{"reason":"Cleared"}""").status shouldBe HttpStatusCode.OK
            connectOnce(ws, driver).shouldBe(null) // welcomed again

            val audit = client.get("/api/v1/admin/audit?targetId=$driverId") { bearerAuth(admin) }
            audit.shouldMatchContract()
            val items = Json.parseToJsonElement(audit.bodyAsText()).jsonObject["items"]!!.jsonArray
            items.map { it.jsonObject["action"]!!.jsonPrimitive.content } shouldBe listOf("DRIVER_ENABLED", "DRIVER_DISABLED")
            items
                .last()
                .jsonObject["reason"]!!
                .jsonPrimitive.content shouldBe "Reported for unsafe driving"
            items.last().jsonObject["requestId"].shouldNotBeNull() // the request id, to find the logs
        }

    @Test
    fun `only an admin changes a driver or reads the audit log, a disable needs a reason, and an unknown driver is 404`() =
        socketApp {
            driverToken()
            change("disable", """{"reason":"x"}""", TestTokens.operator()).status shouldBe HttpStatusCode.Forbidden
            change("disable", """{"reason":"x"}""", TestTokens.driver()).status shouldBe HttpStatusCode.Forbidden
            change("disable", """{}""").apply {
                status shouldBe HttpStatusCode.UnprocessableEntity
                bodyAsText() shouldContain "REASON_REQUIRED"
            }
            change("disable", """{"reason":"x"}""", target = UUID.randomUUID().toString()).status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/admin/audit") { bearerAuth(TestTokens.operator()) }.apply {
                status shouldBe HttpStatusCode.Forbidden
                shouldMatchContract()
            }
        }

    /** A driver account with a row in `users`, through the dev token endpoint, and its token. */
    private suspend fun SocketTestApp.driverToken(): String {
        val response =
            client.post("/api/v1/dev/tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"role":"DRIVER","displayName":"Driver","userId":"$driverId"}""")
            }
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!
            .jsonPrimitive.content
    }

    private suspend fun SocketTestApp.change(
        action: String,
        body: String,
        token: String = admin,
        target: String = driverId,
    ): HttpResponse =
        client.post("/api/v1/admin/drivers/$target/$action") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** Opens a socket and says hello: the close reason when the server closes it, null when it answers welcome. */
    private suspend fun connectOnce(
        ws: HttpClient,
        token: String,
    ): CloseReason? {
        var result: CloseReason? = null
        ws.webSocket("/ws/v1/driver", request = { bearerAuth(token) }) {
            hello()
            val frame = withTimeout(5.seconds) { incoming.receiveCatching().getOrNull() }
            result = if (frame is Frame.Text) null else closeReason.await()
        }
        return result
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.hello() =
        send(
            Frame.Text(
                ProtocolJson.encodeToString(
                    ClientMessage.serializer(),
                    Hello(1, "phone-1", UUID.randomUUID().toString(), sdkVersion = "it"),
                ),
            ),
        )

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receive(): ServerMessage =
        ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())
}
