package veeci.practicing.rts.testing.trip

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.adapter.inbound.web.TripPageResponse
import veeci.practicing.rts.trip.adapter.inbound.web.TripResponse

/** The trip REST API as tests call it: one function per route, returning the raw response. */
class TripApi(
    private val client: HttpClient,
) {
    suspend fun request(
        token: String?,
        pickup: GeoPoint = TripSamples.PICKUP,
        destination: GeoPoint = TripSamples.DESTINATION,
        idempotencyKey: String? = null,
    ): HttpResponse = requestRaw(token, body(pickup, destination), idempotencyKey)

    suspend fun requestRaw(
        token: String?,
        json: String,
        idempotencyKey: String? = null,
    ): HttpResponse =
        client.post(TRIPS) {
            token?.let(::bearerAuth)
            idempotencyKey?.let { header("Idempotency-Key", it) }
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    suspend fun get(
        id: String,
        token: String?,
    ): HttpResponse = client.get("$TRIPS/$id") { token?.let(::bearerAuth) }

    suspend fun listOpen(
        token: String?,
        query: String = "",
    ): HttpResponse = client.get("$TRIPS$query") { token?.let(::bearerAuth) }

    suspend fun act(
        id: String,
        action: String,
        token: String?,
        ifMatch: String? = null,
    ): HttpResponse =
        client.post("$TRIPS/$id/$action") {
            token?.let(::bearerAuth)
            ifMatch?.let { header(HttpHeaders.IfMatch, it) }
        }

    /** Books a trip and returns it; fails if the booking did not succeed. */
    suspend fun created(
        guestToken: String,
        pickup: GeoPoint = TripSamples.PICKUP,
        destination: GeoPoint = TripSamples.DESTINATION,
    ): TripResponse = request(guestToken, pickup, destination).also { check(it.status.value == CREATED) { it.bodyAsText() } }.trip()

    /** Runs [actions] in order (e.g. "accept", "arrive") and returns the trip after the last one. */
    suspend fun moved(
        id: String,
        driverToken: String,
        vararg actions: String,
    ): TripResponse? =
        actions.fold(null as TripResponse?) { _, action ->
            act(id, action, driverToken).also { check(it.status.value == OK) { "$action: ${it.bodyAsText()}" } }.trip()
        }

    companion object {
        const val TRIPS = "/api/v1/trips"
        private const val OK = 200
        private const val CREATED = 201

        fun body(
            pickup: GeoPoint,
            destination: GeoPoint,
        ) = """{"pickup":{"lat":${pickup.lat},"lng":${pickup.lng}},"destination":{"lat":${destination.lat},"lng":${destination.lng}}}"""
    }
}

private val json = Json { ignoreUnknownKeys = true }

suspend fun HttpResponse.trip(): TripResponse = json.decodeFromString(TripResponse.serializer(), bodyAsText())

suspend fun HttpResponse.tripPage(): TripPageResponse = json.decodeFromString(TripPageResponse.serializer(), bodyAsText())
