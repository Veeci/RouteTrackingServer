package veeci.practicing.rts.fleet.adapter.inbound.web

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import veeci.practicing.rts.fleet.application.FleetRebuild
import veeci.practicing.rts.fleet.application.FleetService
import veeci.practicing.rts.fleet.domain.Bbox
import veeci.practicing.rts.fleet.domain.FleetCursor
import veeci.practicing.rts.fleet.domain.FleetFilter
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.security.actor
import java.util.Base64
import java.util.UUID
import kotlin.time.Instant

/**
 * The fleet view for staff (phase 11b, contract in openapi/rts-v1.yaml). Mount inside `authenticate(AUTH_JWT)`. The
 * services decide who may call: OPERATOR and ADMIN read, only ADMIN exports and rebuilds.
 */
fun Route.fleetRoutes(
    service: FleetService,
    rebuild: FleetRebuild,
) {
    get("/api/v1/admin/drivers") {
        val query = DriverListQuery.parse(call.request.queryParameters)
        val page = service.list(call.actor(), query.filter, query.cursor, query.limit)
        call.respond(FleetPageResponse(page.items.map(FleetDriverDto::of), page.next?.let(FleetCursors::encode), page.at))
    }

    get("/api/v1/admin/drivers/{driverId}") {
        val id =
            call.parameters["driverId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw RequestInvalidException(listOf(FieldError("driverId", "must be a UUID")))
        call.respond(DriverDetailResponse.of(service.detail(call.actor(), id)))
    }

    get("/api/v1/admin/overview") {
        call.respond(FleetOverviewResponse.of(service.overview(call.actor())))
    }

    get("/api/v1/admin/drivers.csv") {
        // Checked before the first byte: once the CSV streams, the status is already 200.
        val export = service.export(call.actor())
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "drivers.csv").toString(),
        )
        call.respondTextWriter(ContentType.parse(FleetCsv.CONTENT_TYPE).withParameter("charset", "utf-8")) {
            write(FleetCsv.header())
            export.forEachPage { page ->
                page.forEach { write(FleetCsv.row(it)) }
                flush()
            }
        }
    }

    post("/api/v1/admin/fleet/rebuild") {
        call.respond(FleetRebuildResponse(rebuild.rebuild(call.actor())))
    }
}

/** The driver list's query parameters; every invalid one is reported in one 400 answer. */
internal class DriverListQuery(
    val filter: FleetFilter,
    val cursor: FleetCursor?,
    val limit: Int,
) {
    companion object {
        private const val DEFAULT_LIMIT = 50
        private val LIMITS = 1..100
        private const val MAX_Q = 100
        private const val BBOX_PARTS = 4

        fun parse(parameters: Parameters): DriverListQuery {
            val errors = mutableListOf<FieldError>()

            fun <T> field(
                name: String,
                message: String,
                parse: (String) -> T?,
            ): T? = parameters[name]?.let { text -> parse(text).also { if (it == null) errors += FieldError(name, message) } }

            val online = field("online", "must be true or false", String::toBooleanStrictOrNull)
            val onTrip = field("onTrip", "must be true or false", String::toBooleanStrictOrNull)
            val q = field("q", "must be 1 to $MAX_Q characters") { it.trim().takeIf { q -> q.length in 1..MAX_Q } }
            val bbox = field("bbox", "must be minLng,minLat,maxLng,maxLat within the world, minimum first", ::bbox)
            val cursor = field("cursor", "is not a cursor from this API", FleetCursors::decode)
            val limit = field("limit", "must be ${LIMITS.first} to ${LIMITS.last}") { it.toIntOrNull()?.takeIf { n -> n in LIMITS } }
            if (errors.isNotEmpty()) throw RequestInvalidException(errors)
            return DriverListQuery(FleetFilter(online, onTrip, q, bbox), cursor, limit ?: DEFAULT_LIMIT)
        }

        private fun bbox(text: String): Bbox? {
            val parts = text.split(",").map { it.trim().toDoubleOrNull() }
            if (parts.size != BBOX_PARTS || parts.any { it == null }) return null
            val (minLng, minLat) = parts.filterNotNull().take(2)
            val (maxLng, maxLat) = parts.filterNotNull().drop(2)
            return runCatching { Bbox(minLng, minLat, maxLng, maxLat) }.getOrNull()
        }
    }
}

/** A cursor is opaque to clients: base64url of "lastSeenAt|driverId", with an empty time for a never-seen driver. */
internal object FleetCursors {
    private const val SEPARATOR = "|"

    fun encode(cursor: FleetCursor): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString("${cursor.lastSeenAt ?: ""}$SEPARATOR${cursor.driverId}".toByteArray())

    fun decode(text: String): FleetCursor? =
        runCatching {
            val (seen, id) = String(Base64.getUrlDecoder().decode(text)).split(SEPARATOR, limit = 2)
            FleetCursor(seen.takeIf { it.isNotEmpty() }?.let(Instant::parse), UUID.fromString(id))
        }.getOrNull()
}
