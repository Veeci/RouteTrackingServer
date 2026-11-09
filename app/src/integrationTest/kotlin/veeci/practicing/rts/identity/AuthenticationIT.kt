package veeci.practicing.rts.identity

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.security.AccessTokens
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp
import java.time.Clock
import java.util.UUID

/** Access tokens end to end: the dev token endpoint, and the checks on the driver socket's upgrade request. */
@Testcontainers(disabledWithoutDocker = true)
class AuthenticationIT {
    @Test
    fun `the dev token endpoint creates a user whose token opens the driver socket`() =
        socketApp {
            val response =
                client.post("/api/v1/dev/tokens") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"DRIVER","displayName":"Dana"}""")
                }

            response.status shouldBe HttpStatusCode.OK
            response.shouldMatchContract()
            val token =
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("token")
                    .jsonPrimitive.content
            wsClient().webSocket(DRIVER, request = { bearerAuth(token) }) {
                sayHello() shouldNotBe null
            }
        }

    @Test
    fun `an invalid dev token request is answered with every invalid field`() =
        socketApp {
            val response =
                client.post("/api/v1/dev/tokens") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"PILOT","userId":"not-a-uuid"}""")
                }

            response.status shouldBe HttpStatusCode.BadRequest
            response.problem().errors?.map { it.field } shouldBe listOf("role", "userId")
            response.shouldMatchContract()
        }

    @Test
    fun `TC-3-API-08 the dev token endpoint does not exist in prod`() =
        socketApp(env = mapOf("APP_ENV" to "prod")) {
            val response =
                client.post("/api/v1/dev/tokens") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"ADMIN"}""")
                }

            response.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `the driver socket refuses an upgrade without a token`() =
        socketApp {
            shouldThrowAny { wsClient().webSocket(DRIVER) { sayHello() } }
        }

    @Test
    fun `TC-3-API-07 the driver socket refuses an expired token and a token signed with another key`() =
        socketApp {
            val otherKey =
                AccessTokens(TestConfig.load(mapOf("JWT_SECRET" to "another-signing-key-0123456789abcdef")).jwt, Clock.systemUTC())

            listOf(TestTokens.expired(driver()), otherKey.issue(driver()).token).forEach { token ->
                shouldThrowAny { wsClient().webSocket(DRIVER, request = { bearerAuth(token) }) { sayHello() } }
            }
        }

    @Test
    fun `TC-3-SEC-02 a token for another issuer or audience is refused`() =
        socketApp {
            val config = TestConfig.load().jwt
            val otherIssuer = AccessTokens(config.copy(issuer = "someone-else"), Clock.systemUTC())
            val otherAudience = AccessTokens(config.copy(audience = "another-api"), Clock.systemUTC())

            listOf(otherIssuer, otherAudience).forEach { tokens ->
                shouldThrowAny { wsClient().webSocket(DRIVER, request = { bearerAuth(tokens.issue(driver()).token) }) { sayHello() } }
            }
        }

    @Test
    fun `TC-3-API-09 a guest token on the driver socket closes it with 4403`() =
        socketApp {
            wsClient().webSocket(DRIVER, request = { bearerAuth(TestTokens.guest()) }) {
                closeReason.await()?.code shouldBe WsCloseCodes.FORBIDDEN_ROLE
            }
        }

    @Test
    fun `a socket may send its token as a query parameter`() =
        socketApp {
            wsClient().webSocket("$DRIVER?token=${TestTokens.driver()}") {
                sayHello() shouldNotBe null
            }
        }

    private fun driver() = Actor(TestTokens.newUser(), Role.DRIVER)

    private fun SocketTestApp.wsClient(): HttpClient = createClient { install(WebSockets) }

    /** Sends hello and returns the welcome. */
    private suspend fun DefaultClientWebSocketSession.sayHello(): Welcome? {
        val hello = Hello(1, "phone-1", UUID.randomUUID().toString(), sdkVersion = "0.9.0")
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), hello)))
        return ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText()) as? Welcome
    }

    private companion object {
        const val DRIVER = "/ws/v1/driver"
    }
}
