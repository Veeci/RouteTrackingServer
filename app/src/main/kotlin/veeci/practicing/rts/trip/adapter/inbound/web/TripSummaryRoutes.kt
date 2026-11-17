package veeci.practicing.rts.trip.adapter.inbound.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.trip.application.TripSummaryService
import veeci.practicing.rts.trip.domain.summary.LegSummary
import veeci.practicing.rts.trip.domain.summary.TripSummary
import kotlin.time.Instant

/**
 * Trip summaries (phase 8a, contract in openapi/rts-v1.yaml). Mount inside `authenticate(AUTH_JWT)`. The service
 * decides who may read which summary.
 */
fun Route.tripSummaryRoutes(service: TripSummaryService) {
    get("/api/v1/trips/{id}/summary") {
        call.respond(TripSummaryResponse.of(service.get(call.tripId(), call.actor())))
    }
}

/** An admin computes a summary again; the answer is the new summary. Mount in the admin area, inside `authenticate`. */
fun Route.adminTripSummaryRoutes(service: TripSummaryService) {
    post("/api/v1/admin/trips/{id}/summary") {
        call.respond(TripSummaryResponse.of(service.recompute(call.tripId(), call.actor())))
    }
}

@Serializable
data class TripSummaryResponse(
    val tripId: String,
    val ride: LegSummaryDto,
    /** Only for the driver and admins. */
    val approach: LegSummaryDto? = null,
    val plannedDistanceM: Double? = null,
    val offRouteCount: Int,
    val rerouteCount: Int,
    /** Rejected fixes during the ride, by reason. */
    val rejected: Map<String, Int>,
    /** True for a trip stored before stage times existed. */
    val approximate: Boolean,
    val computedAt: Instant,
) {
    companion object {
        fun of(summary: TripSummary) =
            TripSummaryResponse(
                summary.tripId.value.toString(),
                LegSummaryDto.of(summary.ride),
                summary.approach?.let(LegSummaryDto::of),
                summary.plannedDistanceM,
                summary.offRouteCount,
                summary.rerouteCount,
                summary.rejected,
                summary.approximate,
                summary.computedAt,
            )
    }
}

@Serializable
data class LegSummaryDto(
    val distanceM: Double,
    val durationS: Long,
    val movingTimeS: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val pointCount: Int,
    val startedAt: Instant,
    val endedAt: Instant,
) {
    companion object {
        fun of(leg: LegSummary) =
            LegSummaryDto(
                leg.distanceM,
                leg.durationS,
                leg.movingTimeS,
                leg.avgSpeedMps,
                leg.maxSpeedMps,
                leg.pointCount,
                leg.startedAt,
                leg.endedAt,
            )
    }
}
