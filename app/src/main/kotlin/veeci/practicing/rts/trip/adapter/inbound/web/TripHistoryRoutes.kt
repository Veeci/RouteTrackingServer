package veeci.practicing.rts.trip.adapter.inbound.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.trip.application.TripHistoryItem
import veeci.practicing.rts.trip.application.TripHistoryService
import kotlin.time.Instant

/**
 * The caller's own trips (phase 8b, contract in openapi/rts-v1.yaml). Mount inside `authenticate(AUTH_JWT)`. The
 * token says whose trips and on which side: there is no user or role parameter, so nobody can ask for someone
 * else's history.
 */
fun Route.tripHistoryRoutes(service: TripHistoryService) {
    get("/api/v1/me/trips") {
        val query = PageQuery.parse(call.request.queryParameters)
        val page = service.list(call.actor(), query.cursor, query.limit)
        call.respond(TripHistoryPageResponse(page.items.map(TripHistoryItemDto::of), page.next?.let(TripCursors::encode)))
    }
}

@Serializable
data class TripHistoryPageResponse(
    val items: List<TripHistoryItemDto>,
    /** Pass it as `cursor` to get the next page; absent on the last page. */
    val nextCursor: String? = null,
)

@Serializable
data class TripHistoryItemDto(
    val id: String,
    val guestId: String,
    val driverId: String? = null,
    val pickup: PointDto,
    val destination: PointDto,
    val status: String,
    val createdAt: Instant,
    val acceptedAt: Instant? = null,
    val arrivedAt: Instant? = null,
    val startedAt: Instant? = null,
    /** Set by COMPLETED and by CANCELLED. */
    val endedAt: Instant? = null,
    /** The ride distance; present once the trip is completed and summarized. */
    val rideDistanceM: Double? = null,
) {
    companion object {
        fun of(item: TripHistoryItem) =
            TripHistoryItemDto(
                id = item.trip.id.toString(),
                guestId = item.trip.guestId.toString(),
                driverId = item.trip.driverId?.toString(),
                pickup = PointDto.of(item.trip.pickup),
                destination = PointDto.of(item.trip.destination),
                status = item.trip.status.name,
                createdAt = item.trip.createdAt,
                acceptedAt = item.stages.acceptedAt,
                arrivedAt = item.stages.arrivedAt,
                startedAt = item.stages.startedAt,
                endedAt = item.stages.endedAt,
                rideDistanceM = item.rideDistanceM,
            )
    }
}
