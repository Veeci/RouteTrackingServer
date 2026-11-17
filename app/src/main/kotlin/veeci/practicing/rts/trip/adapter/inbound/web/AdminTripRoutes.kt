package veeci.practicing.rts.trip.adapter.inbound.web

import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.http.etag
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.trip.application.TripAdminService
import veeci.practicing.rts.trip.application.port.out.TripCursor
import veeci.practicing.rts.trip.application.port.out.TripFilter
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Instant

/**
 * Every trip for staff (phase 11d, contract in openapi/rts-v1.yaml): the list and the operator cancel. Mount inside
 * `authenticate(AUTH_JWT)`. The service decides who may call: OPERATOR and ADMIN.
 */
fun Route.adminTripRoutes(service: TripAdminService) {
    get("/api/v1/admin/trips") {
        val query = AdminTripQuery.parse(call.request.queryParameters)
        val page = service.list(call.actor(), query.filter, query.cursor, query.limit)
        call.respond(TripPageResponse(page.items.map(TripResponse::of), page.next?.let(TripCursors::encode)))
    }

    post("/api/v1/admin/trips/{id}/cancel") {
        val body = call.receive<StaffCancelRequest>()
        val trip = service.cancel(call.tripId(), body.reason.orEmpty(), call.actor())
        call.response.header(HttpHeaders.ETag, etag(trip.version))
        call.respond(TripResponse.of(trip))
    }
}

@Serializable
data class StaffCancelRequest(
    /** Required. Kept in the audit log only: the guest and the driver never see it. */
    val reason: String? = null,
)

/** The staff trip list's query parameters; every invalid one is reported in one 400 answer. */
internal class AdminTripQuery(
    val filter: TripFilter,
    val cursor: TripCursor?,
    val limit: Int,
) {
    companion object {
        private const val DEFAULT_LIMIT = 50
        private val LIMITS = 1..100
        private val STATUSES = TripStatus.entries.joinToString { it.name }

        fun parse(parameters: Parameters): AdminTripQuery {
            val errors = mutableListOf<FieldError>()

            fun <T> field(
                name: String,
                message: String,
                parse: (String) -> T?,
            ): T? = parameters[name]?.let { text -> parse(text).also { if (it == null) errors += FieldError(name, message) } }

            val statuses = parameters.getAll("status").orEmpty().map { text -> TripStatus.entries.firstOrNull { it.name == text } }
            if (null in statuses) errors += FieldError("status", "must be one of $STATUSES")
            val driverId = field("driverId", "must be a UUID", ::userId)
            val guestId = field("guestId", "must be a UUID", ::userId)
            val from = field("from", "must be an ISO-8601 time with an offset") { runCatching { Instant.parse(it) }.getOrNull() }
            val to = field("to", "must be an ISO-8601 time with an offset") { runCatching { Instant.parse(it) }.getOrNull() }
            val cursor = field("cursor", "is not a cursor from this API", TripCursors::decode)
            val limit = field("limit", "must be ${LIMITS.first} to ${LIMITS.last}") { it.toIntOrNull()?.takeIf { n -> n in LIMITS } }
            if (from != null && to != null && from >= to) errors += FieldError("to", "must be after from")
            if (errors.isNotEmpty()) throw RequestInvalidException(errors)
            return AdminTripQuery(TripFilter(statuses.filterNotNull().toSet(), driverId, guestId, from, to), cursor, limit ?: DEFAULT_LIMIT)
        }

        private fun userId(text: String): UserId? = runCatching { UUID.fromString(text) }.getOrNull()?.let(::UserId)
    }
}
