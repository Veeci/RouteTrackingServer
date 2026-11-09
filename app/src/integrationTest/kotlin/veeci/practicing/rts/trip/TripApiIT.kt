package veeci.practicing.rts.trip

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.testing.trip.trip
import veeci.practicing.rts.testing.trip.tripPage
import java.util.UUID

/**
 * The trip REST API against the real application and Postgres. Every response is also checked against the
 * OpenAPI contract (TC-3-CON-01), so code and documentation cannot drift apart.
 */
@Testcontainers(disabledWithoutDocker = true)
class TripApiIT {
    private val guest = TestTokens.guest()
    private val driver = TestTokens.driver()

    @Test
    fun `TC-3-API-01 every trip route answers 401 without a token`() =
        testApp {
            val id = UUID.randomUUID().toString()
            val responses =
                listOf(api().request(null), api().listOpen(null), api().get(id, null)) +
                    listOf("accept", "arrive", "start", "complete", "cancel").map { api().act(id, it, null) }

            responses.forEach { response ->
                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe "Bearer"
                response.problem().code shouldBe "UNAUTHORIZED"
                response.shouldMatchContract()
            }
        }

    @Test
    fun `TC-3-API-07 an expired token is refused on the REST API too`() =
        testApp {
            api().listOpen(TestTokens.expired()).status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `TC-3-API-02 a guest books a trip and gets 201 with Location and ETag`() =
        testApp {
            val response = api().request(guest).checked()

            response.status shouldBe HttpStatusCode.Created
            val trip = response.trip()
            trip.status shouldBe "REQUESTED"
            trip.driverId.shouldBeNull()
            response.headers[HttpHeaders.Location] shouldBe "/api/v1/trips/${trip.id}"
            response.headers[HttpHeaders.ETag] shouldBe "\"1\""
            api().get(trip.id, guest).checked().trip() shouldBe trip
        }

    @Test
    fun `TC-3-API-03 invalid coordinates are answered with 400 and every invalid field`() =
        testApp {
            val response = api().request(guest, pickup = GeoPoint(0.0, 0.0), destination = GeoPoint(0.0, 0.0))
            response.status shouldBe HttpStatusCode.BadRequest // same place twice
            response.checked()

            val outOfRange = api().requestRaw(guest, """{"pickup":{"lat":91,"lng":0},"destination":{"lat":0,"lng":-181}}""").checked()

            outOfRange.status shouldBe HttpStatusCode.BadRequest
            val problem = outOfRange.problem()
            problem.code shouldBe "VALIDATION_FAILED"
            problem.errors?.map { it.field } shouldBe listOf("pickup.lat", "destination.lng")
        }

    @Test
    fun `TC-3-API-04 a driver lists only open trips, oldest first, page by page`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val booked = (1..3).map { api().created(guest).id }
            api().act(booked[1], "accept", driver).checked() // no longer open

            val first = api().listOpen(driver, "?status=REQUESTED&limit=1").checked().tripPage()
            val second = api().listOpen(driver, "?limit=1&cursor=${first.nextCursor}").checked().tripPage()

            (first.items + second.items).map { it.id } shouldBe listOf(booked[0], booked[2])
            second.nextCursor.shouldBeNull()
            api().listOpen(driver, "?status=COMPLETED").checked().status shouldBe HttpStatusCode.BadRequest
            api().listOpen(driver, "?cursor=not-a-cursor").checked().status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `TC-3-API-05 the driver moves the trip through every stage`() =
        testApp {
            val id = api().created(guest).id

            listOf("accept" to "ACCEPTED", "arrive" to "ARRIVED", "start" to "IN_PROGRESS", "complete" to "COMPLETED")
                .forEachIndexed { i, (action, status) ->
                    val response = api().act(id, action, driver).checked()

                    response.status shouldBe HttpStatusCode.OK
                    response.trip().status shouldBe status
                    response.headers[HttpHeaders.ETag] shouldBe "\"${i + 2}\""
                }
        }

    @Test
    fun `TC-3-API-06 an action that the trip's stage does not allow is 409`() =
        testApp {
            val id = api().created(guest).id

            val response = api().act(id, "start", driver).checked()

            response.status shouldBe HttpStatusCode.Conflict
            response.problem().code shouldBe "ILLEGAL_TRANSITION"
        }

    @Test
    fun `another guest gets 404 for a trip that exists, the same as for one that does not`() =
        testApp {
            val id = api().created(guest).id

            listOf(api().get(id, TestTokens.guest()), api().get(UUID.randomUUID().toString(), guest)).forEach {
                it.checked().status shouldBe HttpStatusCode.NotFound
                it.problem().code shouldBe "TRIP_NOT_FOUND"
            }
            api().get("not-a-uuid", guest).checked().status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `TC-3-HTTP-01 a retry with the same Idempotency-Key gets the same response and books nothing new`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val key = UUID.randomUUID().toString()

            val first = api().request(guest, idempotencyKey = key).checked()
            val retry = api().request(guest, idempotencyKey = key).checked()

            retry.status shouldBe HttpStatusCode.Created
            retry.bodyAsText() shouldBe first.bodyAsText()
            retry.headers[HttpHeaders.Location] shouldBe first.headers[HttpHeaders.Location]
            retry.headers["Idempotent-Replayed"] shouldBe "true"
            api()
                .listOpen(driver)
                .tripPage()
                .items
                .map { it.id } shouldBe listOf(first.trip().id)
        }

    @Test
    fun `the same Idempotency-Key from two requests at once books one trip`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val key = UUID.randomUUID().toString()

            val responses =
                coroutineScope {
                    List(2) { async(Dispatchers.IO) { api().request(guest, idempotencyKey = key) } }.awaitAll()
                }

            responses.map { it.status } shouldBe listOf(HttpStatusCode.Created, HttpStatusCode.Created)
            responses.map { it.trip().id }.distinct().size shouldBe 1
            api()
                .listOpen(driver)
                .tripPage()
                .items.size shouldBe 1
        }

    @Test
    fun `TC-3-HTTP-02 the same Idempotency-Key with another body is 422`() =
        testApp {
            val key = UUID.randomUUID().toString()
            api().request(guest, idempotencyKey = key).checked()

            val reused = api().request(guest, destination = GeoPoint(10.79, 106.70), idempotencyKey = key).checked()

            reused.status shouldBe HttpStatusCode.UnprocessableEntity
            reused.problem().code shouldBe "IDEMPOTENCY_KEY_REUSED"
        }

    @Test
    fun `two users may use the same Idempotency-Key`() =
        testApp {
            val key = UUID.randomUUID().toString()

            val trips =
                listOf(guest, TestTokens.guest()).map {
                    api()
                        .request(it, idempotencyKey = key)
                        .checked()
                        .trip()
                        .id
                }

            trips.distinct().size shouldBe 2
        }

    @Test
    fun `TC-3-HTTP-03 an action with an outdated If-Match is 412, with the current ETag it succeeds`() =
        testApp {
            val created = api().request(guest).checked()
            val id = created.trip().id
            api().act(id, "cancel", guest, ifMatch = "\"7\"").checked().status shouldBe HttpStatusCode.PreconditionFailed

            val accepted = api().act(id, "accept", driver, ifMatch = created.headers[HttpHeaders.ETag]).checked()

            accepted.status shouldBe HttpStatusCode.OK
            accepted.headers[HttpHeaders.ETag] shouldBe "\"2\""
            api()
                .act(id, "cancel", guest, ifMatch = "\"1\"")
                .checked()
                .problem()
                .code shouldBe "VERSION_MISMATCH"
        }

    @Test
    fun `a driver with an active trip cannot accept a second one`() =
        testApp {
            api().act(api().created(guest).id, "accept", driver).checked()

            val second = api().act(api().created(guest).id, "accept", driver).checked()

            second.status shouldBe HttpStatusCode.Conflict
            second.problem().code shouldBe "DRIVER_ALREADY_ON_TRIP"
        }

    @Test
    fun `a driver who loses the race for a trip hears that it is taken`() =
        testApp {
            val id = api().created(guest).id
            api().act(id, "accept", driver).checked()

            val late = api().act(id, "accept", TestTokens.driver()).checked()

            late.status shouldBe HttpStatusCode.Conflict
            listOf(late.problem().code) shouldContainExactlyInAnyOrder listOf("ILLEGAL_TRANSITION")
        }

    private fun ApplicationTestBuilder.api() = TripApi(client)

    /** Checks the response against the contract and returns it, for chaining. */
    private suspend fun HttpResponse.checked(): HttpResponse = also { it.shouldMatchContract() }
}
