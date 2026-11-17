package veeci.practicing.rts.telemetry

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.BatteryDto
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.DeviceDto
import veeci.practicing.rts.protocol.DeviceStatus
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.LocationProviderDto
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.telemetry.adapter.inbound.web.SessionHealthResponse
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Phase 9b over HTTP: a session recorded over the socket gets its health on an admin's request, and only an admin's. */
@Testcontainers(disabledWithoutDocker = true)
class SessionHealthApiIT {
    private val json = Json { ignoreUnknownKeys = true }
    private val admin = TestTokens.admin()

    @Test
    fun `an admin computes a recorded session's health, reads it back, and both match the contract`() =
        socketApp {
            val session = record()

            val computed = health(session, admin, recompute = true)
            computed.status shouldBe HttpStatusCode.OK
            computed.shouldMatchContract()
            val body = json.decodeFromString(SessionHealthResponse.serializer(), computed.bodyAsText())
            body.fixCount shouldBe FIXES
            body.intervalP50S shouldBe 5.0
            body.primaryProvider shouldBe "AOSP_GPS"
            body.device!!.rom shouldBe "LineageOS 22.1"

            health(session, admin).apply {
                status shouldBe HttpStatusCode.OK
                shouldMatchContract()
            }
        }

    @Test
    fun `TC-9-RPT-04 a driver or a guest gets 403, an unknown session 404, a bad id 400`() =
        socketApp {
            val session = UUID.randomUUID().toString()
            listOf(TestTokens.driver(), TestTokens.guest()).forEach { token ->
                listOf(false, true).forEach { recompute ->
                    health(session, token, recompute).apply {
                        status shouldBe HttpStatusCode.Forbidden
                        bodyAsText() shouldContain "SESSION_HEALTH_FORBIDDEN"
                        shouldMatchContract()
                    }
                }
            }
            listOf(false, true).forEach { recompute ->
                health(session, admin, recompute).apply {
                    status shouldBe HttpStatusCode.NotFound
                    bodyAsText() shouldContain "SESSION_HEALTH_NOT_FOUND"
                    shouldMatchContract()
                }
            }
            client.get("/api/v1/admin/sessions/not-a-uuid/health") { bearerAuth(admin) }.status shouldBe HttpStatusCode.BadRequest
            client.get("/api/v1/admin/sessions/$session/health").status shouldBe HttpStatusCode.Unauthorized
        }

    private suspend fun SocketTestApp.health(
        session: String,
        token: String,
        recompute: Boolean = false,
    ): HttpResponse {
        val path = "/api/v1/admin/sessions/$session/health"
        return if (recompute) client.post(path) { bearerAuth(token) } else client.get(path) { bearerAuth(token) }
    }

    /** A hello with a device, [FIXES] AOSP fixes 5 s apart that end now, and one status sample. */
    private suspend fun SocketTestApp.record(): String {
        val now = Clock.System.now()
        val fixes = (0 until FIXES).map { fixAt(now - ((FIXES - 1 - it) * 5).seconds, it, LocationProviderDto.AOSP_GPS) }
        val device = DeviceDto("Google", "Pixel 7", 35, "LineageOS 22.1", hasGms = false)
        return recordSession(device, fixes, listOf(statusAt(now, "STILL")))
    }

    private companion object {
        const val FIXES = 6
    }
}
