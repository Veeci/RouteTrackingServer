package veeci.practicing.rts.trip

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.trip.adapter.inbound.web.TripHistoryPageResponse
import veeci.practicing.rts.trip.adapter.inbound.web.TripSummaryResponse
import java.util.UUID

/** `GET /api/v1/me/trips` against the real application and Postgres (phase 8b). Paging itself is in TripRepositoryContract. */
@Testcontainers(disabledWithoutDocker = true)
class TripHistoryIT {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `TC-8-HIS-03 a guest's history holds only their own trips, and a driver's only the trips they took`() =
        testApp {
            val api = TripApi(client)
            val guestA = TestTokens.guest()
            val guestB = TestTokens.guest()
            val driver = TestTokens.driver()
            val first = api.created(guestA)
            val second = api.created(guestA)
            val others = api.created(guestB)
            api.moved(others.id, driver, "accept")

            history(guestA)
                .checked()
                .page()
                .items
                .map { it.id } shouldBe listOf(second.id, first.id)
            history(guestB)
                .checked()
                .page()
                .items
                .map { it.id } shouldBe listOf(others.id)
            history(driver)
                .checked()
                .page()
                .items
                .map { it.id } shouldBe listOf(others.id)
            history(TestTokens.driver()).checked().page().items shouldBe emptyList()
        }

    @Test
    fun `a page has a next cursor while more trips follow, and the last page has none`() =
        testApp {
            val api = TripApi(client)
            val guest = TestTokens.guest()
            val trips = (1..3).map { api.created(guest).id }

            val first = history(guest, "?limit=2").checked().page()
            val last = history(guest, "?limit=2&cursor=${first.nextCursor.shouldNotBeNull()}").checked().page()

            (first.items + last.items).map { it.id } shouldBe trips.reversed()
            last.nextCursor.shouldBeNull()
        }

    @Test
    fun `TC-8-HIS-04 a bad cursor or limit is answered with 400 and the field`() =
        testApp {
            val guest = TestTokens.guest()

            listOf("?cursor=not-a-cursor" to "cursor", "?limit=0" to "limit", "?limit=101" to "limit").forEach { (query, field) ->
                val response = history(guest, query).checked()
                response.status shouldBe HttpStatusCode.BadRequest
                response.problem().errors?.map { it.field } shouldBe listOf(field)
            }
        }

    @Test
    fun `an admin has no history and gets 403, and a caller without a token 401`() =
        testApp {
            history(TestTokens.admin()).checked().status shouldBe HttpStatusCode.Forbidden
            history(null).checked().status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `an item has the stage times, and the ride distance once the summary exists`() =
        testApp {
            val api = TripApi(client)
            val guest = TestTokens.guest()
            val driver = TestTokens.driver()
            val cancelled = api.created(guest)
            api.act(cancelled.id, "cancel", guest)
            val done = api.created(guest)
            api.moved(done.id, driver, "accept", "arrive", "start", "complete")
            awaitSummary(done.id)

            val items =
                history(guest)
                    .checked()
                    .page()
                    .items
                    .associateBy { it.id }

            items.getValue(done.id).apply {
                status shouldBe "COMPLETED"
                listOf(acceptedAt, arrivedAt, startedAt, endedAt).forEach { it.shouldNotBeNull() }
                rideDistanceM shouldBe 0.0 // no fixes were sent
            }
            items.getValue(cancelled.id).apply {
                status shouldBe "CANCELLED"
                acceptedAt.shouldBeNull()
                endedAt.shouldNotBeNull()
                rideDistanceM.shouldBeNull()
            }
        }

    @Test
    fun `TC-8-STG-02 a trip stored before stage times existed gets an approximate summary`() =
        testApp {
            val api = TripApi(client)
            val guest = TestTokens.guest()
            val trip = api.created(guest)
            api.moved(trip.id, TestTokens.driver(), "accept", "arrive", "start", "complete")
            awaitSummary(trip.id)
            clearStageTimes(trip.id) // what V13 left on trips stored before it

            val summary = client.post("/api/v1/admin/trips/${trip.id}/summary") { bearerAuth(TestTokens.admin()) }.checked()

            summary.status shouldBe HttpStatusCode.OK
            json.decodeFromString(TripSummaryResponse.serializer(), summary.bodyAsText()).apply {
                approximate shouldBe true
                ride.startedAt shouldBe trip.createdAt // the window falls back to created_at..updated_at
                approach.shouldBeNull() // no accepted_at and arrived_at, so no approach window
            }
        }

    private suspend fun ApplicationTestBuilder.history(
        token: String?,
        query: String = "",
    ): HttpResponse = client.get("/api/v1/me/trips$query") { token?.let(::bearerAuth) }

    private suspend fun HttpResponse.checked(): HttpResponse = also { it.shouldMatchContract() }

    private suspend fun HttpResponse.page() = json.decodeFromString(TripHistoryPageResponse.serializer(), bodyAsText())

    /** The outbox relay computes the summary after the completion commits. */
    private suspend fun ApplicationTestBuilder.awaitSummary(trip: String) {
        val admin = TestTokens.admin()
        val end = System.currentTimeMillis() + WAIT_MS
        while (client.get("${TripApi.TRIPS}/$trip/summary") { bearerAuth(admin) }.status != HttpStatusCode.OK) {
            check(System.currentTimeMillis() < end) { "No summary for trip $trip" }
            delay(POLL_MS)
        }
    }

    private fun clearStageTimes(trip: String) =
        createDataSource(TestConfig.load(TestDatabase.env()).db).use { dataSource ->
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE trips SET accepted_at = NULL, arrived_at = NULL, started_at = NULL, ended_at = NULL WHERE id = ?",
                    ).use {
                        it.setObject(1, UUID.fromString(trip))
                        it.executeUpdate() shouldBe 1
                    }
            }
        }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
