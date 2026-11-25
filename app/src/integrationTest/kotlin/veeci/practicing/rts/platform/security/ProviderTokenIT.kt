package veeci.practicing.rts.platform.security

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestProvider
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.ticket
import java.time.Instant

/**
 * TC-12-AUTH-03 (phase 12g): the identity provider's RS256 tokens, verified against its JWKS, next to the server's own
 * HS256 tokens. [TestProvider] plays the provider on a local port.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProviderTokenIT {
    private val provider = TestProvider()

    @AfterEach
    fun stopProvider() = provider.close()

    private fun app(block: suspend SocketTestApp.() -> Unit) = socketApp(env = provider.env, block = block)

    private suspend fun SocketTestApp.overview(token: String): HttpResponse = client.get(OVERVIEW) { bearerAuth(token) }

    @Test
    fun `a valid provider token with a staff role opens the admin API`() =
        app {
            val response = overview(provider.operator())

            response.status shouldBe HttpStatusCode.OK
            response.shouldMatchContract()
        }

    @Test
    fun `the admin role maps to ADMIN, and an ADMIN-only route answers`() =
        app {
            client.get(AUDIT) { bearerAuth(provider.admin()) }.status shouldBe HttpStatusCode.OK
            client.get(AUDIT) { bearerAuth(provider.operator()) }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `a token from another issuer is refused with 401`() =
        app {
            val response = overview(provider.token(issuer = "https://evil.example/realms/rts"))

            response.status shouldBe HttpStatusCode.Unauthorized
            response.headers[HttpHeaders.WWWAuthenticate] shouldBe "Bearer"
            response.shouldMatchContract()
        }

    @Test
    fun `a token for another audience is refused with 401`() =
        app {
            overview(provider.token(audience = "some-other-api")).status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `an expired token is refused with 401`() =
        app {
            // Outside the 30 s leeway.
            overview(provider.token(expiresAt = Instant.now().minusSeconds(60))).status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `a token whose kid the JWKS does not list is refused with 401, after one more look at the JWKS`() =
        app {
            overview(provider.operator()).status shouldBe HttpStatusCode.OK
            val fetchesBefore = provider.jwksFetches

            val response = overview(provider.token(keyId = "rotated-key", signedWithUnpublishedKey = true))

            response.status shouldBe HttpStatusCode.Unauthorized
            // The cache had no such key, so the server asked the provider again, as it must after a key rotation.
            provider.jwksFetches shouldBeGreaterThan fetchesBefore
        }

    @Test
    fun `a token signed by another key under the published kid is refused with 401`() =
        app {
            overview(provider.token(signedWithUnpublishedKey = true)).status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `a valid token without a staff role is refused with 403 NO_STAFF_ROLE, not 401`() =
        app {
            val response = overview(provider.token(roles = listOf("offline_access", "uma_authorization")))

            response.status shouldBe HttpStatusCode.Forbidden
            response.problem().code shouldBe "NO_STAFF_ROLE"
            response.headers[HttpHeaders.WWWAuthenticate] shouldBe null
            response.shouldMatchContract()
        }

    @Test
    fun `an HS256 token that claims the provider's issuer is refused`() =
        app {
            overview(provider.hs256WithProviderIssuer()).status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `the server's own HS256 tokens still work next to the provider's`() =
        app {
            overview(TestTokens.operator()).status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `a provider token gets a ticket, and the ticket opens the fleet socket`() =
        app {
            val ticket = ticket(provider.operator())

            createClient { install(WebSockets) }.webSocket("/ws/v1/admin/fleet?ticket=$ticket") {
                send(Frame.Text("""{"type":"not_a_message"}"""))
                (incoming.receive() as Frame.Text).readText() shouldContain WsErrorCodes.UNKNOWN_MESSAGE
            }
        }

    @Test
    fun `without OIDC_ISSUER a provider token is refused`() =
        socketApp {
            client.get(OVERVIEW) { bearerAuth(provider.operator()) }.status shouldBe HttpStatusCode.Unauthorized
        }

    private companion object {
        const val OVERVIEW = "/api/v1/admin/overview"
        const val AUDIT = "/api/v1/admin/audit"
    }
}
