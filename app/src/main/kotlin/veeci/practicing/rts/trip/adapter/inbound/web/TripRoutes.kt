package veeci.practicing.rts.trip.adapter.inbound.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import veeci.practicing.rts.platform.http.ApiJson
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.IDEMPOTENCY_KEY_HEADER
import veeci.practicing.rts.platform.http.Idempotency
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.http.StoredResponse
import veeci.practicing.rts.platform.http.etag
import veeci.practicing.rts.platform.http.ifMatchVersion
import veeci.practicing.rts.platform.http.respond
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.trip.application.ActOnTrip
import veeci.practicing.rts.trip.application.TripService
import veeci.practicing.rts.trip.application.api.TripSnapshot
import veeci.practicing.rts.trip.application.port.out.TripCursor
import veeci.practicing.rts.trip.domain.TripAction
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID

/**
 * The trip REST API (`/api/v1/trips`, contract in openapi/rts-v1.yaml). Mount inside `authenticate(AUTH_JWT)`.
 * Each handler only translates: HTTP in → command + actor → service → JSON out. The service decides who may
 * do what; errors it throws become Problem responses in StatusPages.
 */
fun Route.tripRoutes(
    service: TripService,
    idempotency: Idempotency,
) {
    route("/api/v1/trips") {
        // A guest books a trip. Safe to retry with the same Idempotency-Key.
        post {
            val actor = call.actor()
            val body = call.receive<RequestTripBody>() // validated (400) before this line returns
            // What makes two requests "the same request" for the idempotency check.
            val request =
                "${call.request.httpMethod.value} ${call.request.path()} " + ApiJson.encodeToString(RequestTripBody.serializer(), body)
            val result =
                idempotency.execute(
                    key = call.request.headers[IDEMPOTENCY_KEY_HEADER],
                    principal = actor.userId.value.toString(),
                    request = request,
                ) {
                    val trip = service.request(body.toCommand(), actor)
                    StoredResponse(
                        HttpStatusCode.Created,
                        mapOf(HttpHeaders.Location to "/api/v1/trips/${trip.id}", HttpHeaders.ETag to etag(trip.version)),
                        ApiJson.encodeToString(TripResponse.serializer(), TripResponse.of(trip)),
                    )
                }
            call.respond(result)
        }

        // Open trips for drivers, oldest first, one page at a time.
        get {
            val query = TripListQuery.parse(call.request.queryParameters)
            val page = service.listOpen(call.actor(), query.cursor, query.limit)
            call.respond(TripPageResponse(page.items.map(TripResponse::of), page.next?.let(TripCursors::encode)))
        }

        get("/{id}") {
            call.respondTrip(service.get(call.tripId(), call.actor()))
        }

        // One route per action: POST /api/v1/trips/{id}/accept, .../arrive, .../start, .../complete, .../cancel
        TripAction.entries.forEach { action ->
            post("/{id}/${action.name.lowercase()}") {
                val command = ActOnTrip(call.tripId(), action, expectedVersion = call.ifMatchVersion())
                call.respondTrip(service.act(command, call.actor()))
            }
        }
    }
}

private suspend fun ApplicationCall.respondTrip(trip: TripSnapshot) {
    response.header(HttpHeaders.ETag, etag(trip.version))
    respond(TripResponse.of(trip))
}

private fun ApplicationCall.tripId(): TripId {
    val raw = parameters["id"]
    val uuid = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    return TripId(uuid ?: throw RequestInvalidException(listOf(FieldError("id", "must be a UUID"))))
}

/** `?status=REQUESTED&cursor=...&limit=20`. Only open (REQUESTED) trips can be listed for now. */
private data class TripListQuery(
    val cursor: TripCursor?,
    val limit: Int,
) {
    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100

        fun parse(parameters: Parameters): TripListQuery {
            val status = parameters["status"]
            val limitText = parameters["limit"]
            val cursorText = parameters["cursor"]
            val cursor = cursorText?.let(TripCursors::decode)
            val errors =
                buildList {
                    if (status != null && status != TripStatus.REQUESTED.name) add(FieldError("status", "only REQUESTED can be listed"))
                    if (limitText != null &&
                        (limitText.toIntOrNull() ?: 0) !in 1..MAX_LIMIT
                    ) {
                        add(FieldError("limit", "must be 1 to $MAX_LIMIT"))
                    }
                    if (cursorText != null && cursor == null) add(FieldError("cursor", "is not a cursor from this API"))
                }
            if (errors.isNotEmpty()) throw RequestInvalidException(errors)
            return TripListQuery(cursor, limitText?.toInt() ?: DEFAULT_LIMIT)
        }
    }
}
