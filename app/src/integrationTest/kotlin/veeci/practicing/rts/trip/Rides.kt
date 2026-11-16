package veeci.practicing.rts.trip

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.geo.offset
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import java.util.UUID
import kotlin.time.Instant

/** A fix [northM] metres north of the pickup, recorded at [at]. */
internal fun rideFix(
    northM: Double,
    at: Instant,
): FixDto {
    val point = PICKUP.offset(northM = northM, eastM = 0.0)
    return FixDto(point.lat, point.lng, accuracyM = 5.0, speedMps = 10.0, recordedAt = at)
}

/** One session of [driver]: hello, then all [fixes] in one batch, waiting until the server accepted every one. */
internal suspend fun SocketTestApp.sendRide(
    driver: String,
    fixes: List<FixDto>,
) = createClient { install(WebSockets) }.webSocket("/ws/v1/driver", request = { bearerAuth(driver) }) {
    suspend fun send(message: ClientMessage) = send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    suspend fun receive() = ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())
    send(Hello(1, "phone-1", UUID.randomUUID().toString(), sdkVersion = "0.9.0"))
    receive().shouldBeInstanceOf<Welcome>()
    send(FixBatch(1, fixes))
    while (true) {
        val message = receive()
        if (message is Ack && message.seq == 1L) {
            message.accepted shouldBe fixes.size
            break
        }
    }
}
