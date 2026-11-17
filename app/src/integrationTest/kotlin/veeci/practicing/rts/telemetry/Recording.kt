package veeci.practicing.rts.telemetry

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import java.util.UUID
import kotlin.time.Instant

/** A fix at [at], [index] steps of about 11 m north of Hoan Kiem lake. */
internal fun fixAt(
    at: Instant,
    index: Int,
    provider: LocationProviderDto,
) = FixDto(21.0285 + index * 1e-4, 105.8542, accuracyM = 5.0, recordedAt = at, provider = provider)

/** A not-charging status at [at] with [motion]. */
internal fun statusAt(
    at: Instant,
    motion: String,
) = DeviceStatus(at, BatteryDto(levelPercent = 80, isCharging = false), motion = motion)

/**
 * One driver session over the socket: hello with [device], every status, then all [fixes] in one batch, waiting
 * until the server acked it. Returns the session id.
 */
internal suspend fun SocketTestApp.recordSession(
    device: DeviceDto,
    fixes: List<FixDto>,
    statuses: List<DeviceStatus> = emptyList(),
): String {
    val session = UUID.randomUUID().toString()
    createClient { install(WebSockets) }.webSocket("/ws/v1/driver", request = { bearerAuth(TestTokens.driver()) }) {
        suspend fun send(message: ClientMessage) = send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

        suspend fun receive() = ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())
        send(Hello(1, "phone-1", session, sdkVersion = "0.10.0", device = device))
        receive().shouldBeInstanceOf<Welcome>()
        statuses.forEach { send(it) }
        send(FixBatch(1, fixes))
        while (true) {
            val message = receive()
            if (message is Ack && message.seq == 1L) {
                message.accepted shouldBe fixes.size
                break
            }
        }
    }
    return session
}

/** Computes the session's health now, as an admin. */
internal suspend fun SocketTestApp.computeHealth(session: String) {
    client.post("/api/v1/admin/sessions/$session/health") { bearerAuth(TestTokens.admin()) }.status shouldBe HttpStatusCode.OK
}
