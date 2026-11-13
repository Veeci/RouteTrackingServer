package veeci.practicing.rts.devicecontrol

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.protocol.GuestClientMessage
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Subscribe
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.trip.TripApi
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** The automatic policies end to end: trip stages and a watching guest reach the connected driver as commands. */
@Testcontainers(disabledWithoutDocker = true)
class CommandPoliciesIT {
    private val guestId = TestTokens.newUser()
    private val driverId = TestTokens.newUser()
    private val guest = TestTokens.guest(guestId)
    private val driver = TestTokens.driver(driverId)

    @Test
    fun `TC-5-E2E-01 accepting gives the driver a pickup geofence, and a watching guest a high accuracy`() =
        socketApp {
            val trip = TripApi(client).created(guest)

            createClient { install(WebSockets) }.webSocket("/ws/v1/driver", request = { bearerAuth(driver) }) {
                val driverSocket = this
                withTimeout(10.seconds) {
                    sendClient(Hello(1, "phone-1", UUID.randomUUID().toString(), sdkVersion = "0.9.0"))
                    receiveServer().shouldBeInstanceOf<Welcome>()

                    TripApi(client).act(trip.id, "accept", driver)
                    val fence = receiveServer().shouldBeInstanceOf<Command>()
                    fence.kind shouldBe "REGISTER_GEOFENCES"
                    fence.regions!!.single().id shouldBe "pickup"

                    createClient { install(WebSockets) }.webSocket("/ws/v1/guest", request = { bearerAuth(guest) }) {
                        send(Frame.Text(ProtocolJson.encodeToString(GuestClientMessage.serializer(), Subscribe(trip.id))))
                        incoming.receive() // subscribed
                        val high = driverSocket.receiveServer().shouldBeInstanceOf<Command>()
                        high.kind shouldBe "SET_CONSTRAINT"
                        high.maxAccuracy shouldBe "HIGH"
                    }
                }
            }
        }

    private suspend fun DefaultClientWebSocketSession.sendClient(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    private suspend fun DefaultClientWebSocketSession.receiveServer(): ServerMessage =
        ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())
}
