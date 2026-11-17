package veeci.practicing.rts.trip

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.audit.AuditPage
import veeci.practicing.rts.protocol.ClientMessage
import veeci.practicing.rts.protocol.GuestClientMessage
import veeci.practicing.rts.protocol.GuestServerMessage
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.protocol.Subscribe
import veeci.practicing.rts.protocol.Subscribed
import veeci.practicing.rts.protocol.TripEvent
import veeci.practicing.rts.protocol.Welcome
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.testing.trip.trip
import veeci.practicing.rts.testing.trip.tripPage
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** The staff trip API of phase 11d: the operator cancel, seen live by the guest and the driver, and the trip list. */
@Testcontainers(disabledWithoutDocker = true)
class AdminTripsIT {
    private val guestUser = TestTokens.newUser()
    private val guest = TestTokens.guest(guestUser)
    private val guestId = guestUser.value
    private val driverUser = TestTokens.newUser()
    private val driver = TestTokens.driver(driverUser)
    private val driverId = driverUser.value
    private val operator = TestTokens.operator()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `TC-11-API-03 an operator cancels a ride in progress, the guest and the driver both get CANCELLED by OPERATOR`() =
        socketApp {
            val trip = api().created(guest).id
            api().moved(trip, driver, "accept", "arrive", "start")
            val driverReady = CompletableDeferred<Unit>()
            val guestReady = CompletableDeferred<Unit>()

            val (forGuest, forDriver) =
                coroutineScope {
                    val guestSide = async { watchUntilCancelled(trip, guestReady) }
                    val driverSide = async { driveUntilCancelled(driverReady) }
                    guestReady.await()
                    driverReady.await()
                    val response = cancel(trip, """{"reason":"The guest called: wrong car"}""")
                    response.status shouldBe HttpStatusCode.OK
                    response.shouldMatchContract()
                    response.trip().status shouldBe "CANCELLED"
                    guestSide.await() to driverSide.await()
                }

            forGuest.status shouldBe "CANCELLED"
            forGuest.cancelledBy shouldBe "OPERATOR"
            forDriver.tripId shouldBe trip
            forDriver.status shouldBe "CANCELLED"
            forDriver.cancelledBy shouldBe "OPERATOR"
            // The reason is for staff only: it never travels to a client.
            ProtocolJson.encodeToString(TripEvent.serializer(), forGuest) shouldNotContain "wrong car"

            val audit = client.get("/api/v1/admin/audit?action=TRIP_CANCELLED&targetId=$trip") { bearerAuth(TestTokens.admin()) }
            val record = json.decodeFromString(AuditPage.serializer(), audit.bodyAsText()).items.single()
            record.reason shouldBe "The guest called: wrong car"
            record.actorRole shouldBe "OPERATOR"
            record.targetType shouldBe "TRIP"
            record.details shouldBe mapOf("from" to "IN_PROGRESS")
        }

    @Test
    fun `an ended trip answers 409, a missing reason 422, an unknown trip 404, and a guest cancel still says GUEST`() =
        socketApp {
            val done = api().created(guest).id
            api().moved(done, driver, "accept", "arrive", "start", "complete")
            cancel(done, """{"reason":"late"}""").apply {
                status shouldBe HttpStatusCode.Conflict
                bodyAsText() shouldContain "ILLEGAL_TRANSITION"
            }

            val open = api().created(guest).id
            cancel(open, """{"reason":"   "}""").apply {
                status shouldBe HttpStatusCode.UnprocessableEntity
                shouldMatchContract()
            }
            cancel(open, "{}").status shouldBe HttpStatusCode.UnprocessableEntity
            cancel(UUID.randomUUID().toString(), """{"reason":"x"}""").status shouldBe HttpStatusCode.NotFound
            // The user route stays closed to staff: their cancel needs a reason and an audit row.
            api().act(open, "cancel", operator).status shouldBe HttpStatusCode.Forbidden
            api().act(open, "cancel", guest).status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `TC-11-API-06 the trip list filters by repeated status, guest, driver and time, pages newest first`() =
        socketApp {
            val trips = (1..4).map { api().created(guest).id }
            api().moved(trips[1], driver, "accept")
            api().moved(trips[2], TestTokens.driver(), "accept", "arrive") // a driver drives one trip at a time
            val newest = trips.reversed()

            list("guestId=$guestId&limit=3").apply {
                status shouldBe HttpStatusCode.OK
                shouldMatchContract()
            }
            pages("guestId=$guestId", size = 3) shouldContainExactly newest
            pages("guestId=$guestId&status=ACCEPTED&status=ARRIVED", size = 1) shouldContainExactly listOf(trips[2], trips[1])
            pages("guestId=$guestId&driverId=$driverId", size = 50) shouldContainExactly listOf(trips[1])
            // `from` is inclusive and `to` exclusive.
            val third = api().get(trips[2], guest).trip().createdAt
            pages("guestId=$guestId&from=$third", size = 10) shouldContainExactly listOf(trips[3], trips[2])
            pages("guestId=$guestId&to=$third", size = 10) shouldContainExactly listOf(trips[1], trips[0])

            list("status=STARTED&limit=0&driverId=nope").apply {
                status shouldBe HttpStatusCode.BadRequest
                bodyAsText().let { body -> listOf("status", "limit", "driverId").forEach { body.contains("\"$it\"") shouldBe true } }
            }
            list("guestId=$guestId", token = guest).status shouldBe HttpStatusCode.Forbidden
        }

    private fun SocketTestApp.api() = TripApi(client)

    private fun SocketTestApp.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun SocketTestApp.cancel(
        trip: String,
        body: String,
    ): HttpResponse =
        client.post("/api/v1/admin/trips/$trip/cancel") {
            bearerAuth(operator)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun SocketTestApp.list(
        query: String,
        token: String = operator,
    ): HttpResponse = client.get("/api/v1/admin/trips?$query") { bearerAuth(token) }

    /** Every trip id the query matches, read page by page through `nextCursor`, as the dashboard does. */
    private suspend fun SocketTestApp.pages(
        query: String,
        size: Int,
    ): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = list("$query&limit=$size" + (cursor?.let { "&cursor=$it" } ?: "")).tripPage()
            ids += page.items.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)
        return ids
    }

    /** The guest watches [trip] and returns the `trip_event` that ended it. */
    private suspend fun SocketTestApp.watchUntilCancelled(
        trip: String,
        ready: CompletableDeferred<Unit>,
    ): TripEvent {
        var ended: TripEvent? = null
        wsClient().webSocket("/ws/v1/guest", request = { bearerAuth(guest) }) {
            withTimeout(TIMEOUT) {
                send(Frame.Text(ProtocolJson.encodeToString(GuestClientMessage.serializer(), Subscribe(trip))))
                receiveGuest().shouldBeInstanceOf<Subscribed>()
                ready.complete(Unit)
                while (ended == null) {
                    (receiveGuest() as? TripEvent)?.takeIf { it.status == "CANCELLED" }?.let { ended = it }
                }
            }
        }
        return ended.shouldNotBeNull()
    }

    /** The driver's phone is connected and returns the `trip_event` the server sends when the trip is cancelled. */
    private suspend fun SocketTestApp.driveUntilCancelled(ready: CompletableDeferred<Unit>): TripEvent {
        var ended: TripEvent? = null
        wsClient().webSocket("/ws/v1/driver", request = { bearerAuth(driver) }) {
            withTimeout(TIMEOUT) {
                send(
                    Frame.Text(
                        ProtocolJson.encodeToString(
                            ClientMessage.serializer(),
                            Hello(1, "phone-1", UUID.randomUUID().toString(), sdkVersion = "0.9.0"),
                        ),
                    ),
                )
                receiveDriver().shouldBeInstanceOf<Welcome>()
                ready.complete(Unit)
                // Commands and routes may come first; only the trip event matters here.
                while (ended == null) ended = receiveDriver() as? TripEvent
            }
        }
        return ended.shouldNotBeNull().also { it.event shouldBe TripEvent.STATUS_CHANGED }
    }

    private suspend fun DefaultClientWebSocketSession.receiveGuest(): GuestServerMessage =
        ProtocolJson.decodeFromString(GuestServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())

    private suspend fun DefaultClientWebSocketSession.receiveDriver(): ServerMessage =
        ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())

    private companion object {
        val TIMEOUT = 15.seconds
    }
}
