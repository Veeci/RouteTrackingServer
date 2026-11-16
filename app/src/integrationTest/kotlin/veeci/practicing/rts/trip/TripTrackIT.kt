package veeci.practicing.rts.trip

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeSortedBy
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.geo.GpxTrack
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.trip.adapter.inbound.web.TripTrackResponse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** `GET /api/v1/trips/{id}/track` on Netty with a ride recorded over the driver socket (phase 8c). */
@Testcontainers(disabledWithoutDocker = true)
class TripTrackIT {
    private val guest = TestTokens.guest()
    private val driver = TestTokens.driver()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the ride comes back in order with the trip's timeline, and simplify keeps only the ends of a straight line`() =
        socketApp {
            val api = TripApi(client)
            val trip = api.created(guest)
            api.moved(trip.id, driver, "accept", "arrive", "start")
            // The server refuses fixes from the future: wait, then send fixes that lie between the start and now.
            val started = nowInMillis()
            delay(RIDE_MS)
            sendRide(driver, (1..POINTS).map { rideFix(northM = it * 10.0, at = started + (it * STEP_MS).milliseconds) })
            api.moved(trip.id, driver, "complete")

            val ride = track(trip.id, guest).checked().track()

            ride.leg shouldBe "ride"
            ride.points.size shouldBe POINTS
            ride.points.shouldBeSortedBy { it.at }
            ride.points.first().speedMps shouldBe 10.0
            ride.timeline.shouldBeSortedBy { it.at }
            ride.timeline.filter { it.kind == "status_changed" }.map { it.status } shouldBe
                listOf("REQUESTED", "ACCEPTED", "ARRIVED", "IN_PROGRESS", "COMPLETED")
            ride.timeline.map { it.kind to it.routeKind } shouldContain ("route_updated" to "TRIP")

            val simplified = track(trip.id, guest, "?simplify=5").checked().track()
            simplified.points shouldBe listOf(ride.points.first(), ride.points.last())
            simplified.timeline shouldBe ride.timeline
        }

    @Test
    fun `TC-8-API-01 and 02 a live trip gets 409, and a guest asking for the approach 403 while the driver gets it`() =
        socketApp {
            val api = TripApi(client)
            val trip = api.created(guest)
            api.moved(trip.id, driver, "accept")

            track(trip.id, guest).checked().apply {
                status shouldBe HttpStatusCode.Conflict
                problem().code shouldBe "TRIP_NOT_ENDED"
            }

            api.moved(trip.id, driver, "arrive", "start", "complete")
            track(trip.id, guest, "?leg=approach").checked().status shouldBe HttpStatusCode.Forbidden
            track(trip.id, driver, "?leg=approach").checked().apply {
                status shouldBe HttpStatusCode.OK
                track().leg shouldBe "approach"
                track().points.shouldBeEmpty() // the driver sent no fixes before the pickup
            }
        }

    @Test
    fun `another guest gets 404, and a bad leg or simplify gets 400 with the field`() =
        socketApp {
            val api = TripApi(client)
            val trip = api.created(guest)
            api.act(trip.id, "cancel", guest)

            track(trip.id, TestTokens.guest()).checked().status shouldBe HttpStatusCode.NotFound
            track(trip.id, TestTokens.driver()).checked().status shouldBe HttpStatusCode.NotFound
            listOf("?leg=walk" to "leg", "?simplify=51" to "simplify", "?simplify=-1" to "simplify", "?simplify=far" to "simplify")
                .forEach { (query, field) ->
                    track(trip.id, guest, query).checked().apply {
                        status shouldBe HttpStatusCode.BadRequest
                        problem().errors?.map { it.field } shouldBe listOf(field)
                    }
                }
            track(trip.id, guest)
                .checked()
                .track()
                .points
                .shouldBeEmpty() // cancelled before anyone accepted it
        }

    @Test
    fun `TC-8-E2E-01 and TC-8-EXP-03 a recorded ride downloads as GPX and GeoJSON that parse back`() =
        socketApp {
            val api = TripApi(client)
            val trip = api.created(guest)
            api.moved(trip.id, driver, "accept", "arrive", "start")
            val started = nowInMillis()
            delay(RIDE_MS)
            val sent = (1..POINTS).map { rideFix(northM = it * 10.0, at = started + (it * STEP_MS).milliseconds) }
            sendRide(driver, sent)
            export(trip.id, guest, "gpx").checked().status shouldBe HttpStatusCode.Conflict // still running
            api.moved(trip.id, driver, "complete")

            val gpx = export(trip.id, guest, "gpx").checked()
            gpx.status shouldBe HttpStatusCode.OK
            gpx.contentType()?.withoutParameters() shouldBe ContentType.parse("application/gpx+xml")
            gpx.headers[HttpHeaders.ContentDisposition] shouldBe "attachment; filename=trip-${trip.id}.gpx"
            GpxTrack.points(gpx.bodyAsText()).map { it.time } shouldBe sent.map { it.recordedAt }

            val geoJson = export(trip.id, guest, "geojson").checked()
            geoJson.contentType()?.withoutParameters() shouldBe ContentType.parse("application/geo+json")
            geoJson.headers[HttpHeaders.ContentDisposition] shouldBe "attachment; filename=trip-${trip.id}.geojson"
            val line =
                Json
                    .parseToJsonElement(geoJson.bodyAsText())
                    .jsonObject["features"]!!
                    .jsonArray
                    .first()
                    .jsonObject["geometry"]!!
            line.jsonObject["coordinates"]!!.jsonArray.size shouldBe POINTS

            export(trip.id, guest, "gpx", "?leg=approach").checked().status shouldBe HttpStatusCode.Forbidden
            export(trip.id, TestTokens.guest(), "geojson").checked().status shouldBe HttpStatusCode.NotFound
        }

    /**
     * Now, cut to whole milliseconds. Postgres stores microseconds, and Linux clocks give nanoseconds, so a fix
     * sent at Clock.System.now() can come back rounded and no longer equal the time that was sent.
     */
    private fun nowInMillis() = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun SocketTestApp.export(
        tripId: String,
        token: String,
        format: String,
        query: String = "",
    ): HttpResponse = client.get("/api/v1/trips/$tripId/export.$format$query") { bearerAuth(token) }

    private suspend fun SocketTestApp.track(
        tripId: String,
        token: String,
        query: String = "",
    ): HttpResponse = client.get("/api/v1/trips/$tripId/track$query") { bearerAuth(token) }

    private suspend fun HttpResponse.checked(): HttpResponse = also { it.shouldMatchContract() }

    private suspend fun HttpResponse.track() = json.decodeFromString(TripTrackResponse.serializer(), bodyAsText())

    private companion object {
        const val POINTS = 20
        const val STEP_MS = 100
        const val RIDE_MS = 2_200L
    }
}
