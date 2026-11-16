package veeci.practicing.rts.trip

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.geo.offset
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import veeci.practicing.rts.trip.adapter.inbound.web.TripSummaryResponse
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Phase 8a end to end on Netty: a driver records a short ride over the socket, completes the trip, and the summary
 * is computed through the outbox (TC-8-E2E-01, the summary half).
 */
@Testcontainers(disabledWithoutDocker = true)
class TripSummaryIT {
    private val guestId = TestTokens.newUser()
    private val guest = TestTokens.guest(guestId)
    private val driver = TestTokens.driver(TestTokens.newUser())
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `TC-8-E2E-01 a completed ride gets a summary, and only the driver sees the approach`() =
        socketApp {
            val api = TripApi(client)
            val trip = api.created(guest)
            api.moved(trip.id, driver, "accept", "arrive", "start")
            summary(trip.id, guest).status shouldBe HttpStatusCode.NotFound

            // Fixes must be recorded during the ride, and the server refuses times in the future: wait, then send
            // 25 fixes 100 ms and 1 m apart that lie between the start and now.
            val started = Clock.System.now()
            delay(RIDE_MS)
            sendRide((1..POINTS).map { fix(northM = it.toDouble(), at = started + (it * STEP_MS).milliseconds) })
            api.moved(trip.id, driver, "complete")

            val forGuest = eventuallySummary(trip.id, guest)
            forGuest.shouldMatchContract()
            val summary = forGuest.summary()
            summary.ride.pointCount shouldBe POINTS
            summary.ride.distanceM shouldBe ((POINTS - 1).toDouble() plusOrMinus 0.5)
            summary.approximate shouldBe false
            summary.plannedDistanceM.shouldNotBeNull()
            summary.approach.shouldBeNull()

            summary(trip.id, driver).summary().approach.shouldNotBeNull()
            summary(trip.id, TestTokens.guest()).status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `only an admin computes a summary again, and only for a completed trip`() =
        socketApp {
            val api = TripApi(client)
            val trip = api.created(guest)
            api.moved(trip.id, driver, "accept")
            val admin = TestTokens.admin()

            recompute(trip.id, admin).apply {
                status shouldBe HttpStatusCode.Conflict
                bodyAsText() shouldContain "TRIP_NOT_ENDED"
                shouldMatchContract()
            }

            api.moved(trip.id, driver, "arrive", "start", "complete")
            recompute(trip.id, guest).status shouldBe HttpStatusCode.Forbidden
            recompute(trip.id, admin).apply {
                status shouldBe HttpStatusCode.OK
                shouldMatchContract()
                summary().ride.pointCount shouldBe 0
            }
        }

    private suspend fun SocketTestApp.summary(
        tripId: String,
        token: String,
    ): HttpResponse = client.get("/api/v1/trips/$tripId/summary") { bearerAuth(token) }

    private suspend fun SocketTestApp.recompute(
        tripId: String,
        token: String,
    ): HttpResponse = client.post("/api/v1/admin/trips/$tripId/summary") { bearerAuth(token) }

    /** The summary is computed after the completion commits, by the outbox relay. */
    private suspend fun SocketTestApp.eventuallySummary(
        tripId: String,
        token: String,
    ): HttpResponse {
        val end = System.currentTimeMillis() + WAIT_MS
        while (true) {
            val response = summary(tripId, token)
            if (response.status == HttpStatusCode.OK || System.currentTimeMillis() > end) return response
            delay(POLL_MS)
        }
    }

    private suspend fun HttpResponse.summary() = json.decodeFromString(TripSummaryResponse.serializer(), bodyAsText())

    private fun fix(
        northM: Double,
        at: Instant,
    ): FixDto {
        val point = PICKUP.offset(northM = northM, eastM = 0.0)
        return FixDto(point.lat, point.lng, accuracyM = 5.0, speedMps = 10.0, recordedAt = at)
    }

    /** One driver session: hello, then all [fixes] in one batch, waiting for its ack. */
    private suspend fun SocketTestApp.sendRide(fixes: List<FixDto>) =
        createClient { install(WebSockets) }.webSocket("/ws/v1/driver", request = { bearerAuth(driver) }) {
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

    private companion object {
        const val POINTS = 25
        const val STEP_MS = 100
        const val RIDE_MS = 2_700L
        const val WAIT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
