package veeci.practicing.rts.trip.adapter.inbound.web

import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.trip.application.TripTrack
import veeci.practicing.rts.trip.application.TripTrackService
import veeci.practicing.rts.trip.domain.TimelineEntry
import veeci.practicing.rts.trip.domain.TripLeg
import veeci.practicing.rts.trip.domain.event.TripMoment
import kotlin.time.Instant

/**
 * The recorded track and timeline of an ended trip (phase 8c, contract in openapi/rts-v1.yaml). Mount inside
 * `authenticate(AUTH_JWT)`. The service decides who may read which leg.
 */
fun Route.tripTrackRoutes(service: TripTrackService) {
    get("/api/v1/trips/{id}/track") {
        val query = TrackQuery.parse(call.request.queryParameters)
        call.respond(TripTrackResponse.of(service.track(call.tripId(), call.actor(), query.leg, query.simplifyM)))
    }
}

/** `?leg=ride&simplify=0`. Both are optional. */
private data class TrackQuery(
    val leg: TripLeg,
    val simplifyM: Double,
) {
    companion object {
        fun parse(parameters: Parameters): TrackQuery {
            val legText = parameters["leg"]
            val simplifyText = parameters["simplify"]
            val leg = if (legText == null) TripLeg.RIDE else TripLeg.entries.firstOrNull { it.name.lowercase() == legText }
            val simplifyM =
                if (simplifyText ==
                    null
                ) {
                    0.0
                } else {
                    simplifyText.toDoubleOrNull()?.takeIf { it in 0.0..TripTrackService.MAX_SIMPLIFY_M }
                }
            val errors =
                buildList {
                    if (leg == null) add(FieldError("leg", "must be ride or approach"))
                    if (simplifyM ==
                        null
                    ) {
                        add(FieldError("simplify", "must be a number of metres from 0 to ${TripTrackService.MAX_SIMPLIFY_M.toInt()}"))
                    }
                }
            if (errors.isNotEmpty()) throw RequestInvalidException(errors)
            return TrackQuery(checkNotNull(leg), checkNotNull(simplifyM))
        }
    }
}

@Serializable
data class TripTrackResponse(
    val tripId: String,
    /** `ride` or `approach`, as asked. */
    val leg: String,
    val points: List<TrackPointDto>,
    val timeline: List<TimelineEntryDto>,
) {
    companion object {
        fun of(track: TripTrack) =
            TripTrackResponse(
                track.tripId.value.toString(),
                track.leg.name.lowercase(),
                track.points.map { TrackPointDto(it.lat, it.lng, it.recordedAt, it.speedMps ?: it.derivedSpeedMps) },
                track.timeline.map(TimelineEntryDto::of),
            )
    }
}

@Serializable
data class TrackPointDto(
    val lat: Double,
    val lng: Double,
    val at: Instant,
    /** The phone's speed, or the speed from the previous fix when the phone sent none; absent for neither. */
    val speedMps: Double? = null,
)

/**
 * One timeline entry. [kind] uses the guest socket's names: `status_changed` and the moment names from
 * `trip_event`, and `route_updated` for a route version. Only the fields of that kind are present.
 */
@Serializable
data class TimelineEntryDto(
    val at: Instant,
    val kind: String,
    /** status_changed: the stage reached. A moment: the trip's stage when it happened. */
    val status: String? = null,
    val remainingM: Double? = null,
    val levelPercent: Int? = null,
    val lastSeenAt: Instant? = null,
    val routeKind: String? = null,
    val routeVersion: Int? = null,
    val reason: String? = null,
    val distanceM: Double? = null,
    val durationS: Double? = null,
    val polyline: String? = null,
) {
    companion object {
        fun of(entry: TimelineEntry): TimelineEntryDto =
            when (entry) {
                is TimelineEntry.StageReached -> {
                    TimelineEntryDto(entry.at, "status_changed", status = entry.status.name)
                }

                is TimelineEntry.MomentRaised -> {
                    moment(entry.moment)
                }

                is TimelineEntry.RoutePlanned -> {
                    entry.route.let {
                        TimelineEntryDto(
                            it.createdAt,
                            "route_updated",
                            routeKind = it.kind.name,
                            routeVersion = it.version,
                            reason = it.reason.name.lowercase(),
                            distanceM = it.distanceM,
                            durationS = it.durationS,
                            polyline = it.polyline,
                        )
                    }
                }
            }

        private fun moment(m: TripMoment): TimelineEntryDto {
            val base = TimelineEntryDto(m.at, "", status = m.status.name)
            return when (m) {
                is TripMoment.DriverArriving -> base.copy(kind = "driver_arriving", remainingM = m.remainingM)
                is TripMoment.DriverLowBattery -> base.copy(kind = "driver_low_battery", levelPercent = m.levelPercent)
                is TripMoment.DriverStale -> base.copy(kind = "driver_stale", lastSeenAt = m.lastSeenAt)
                is TripMoment.DriverBack -> base.copy(kind = "driver_back")
                is TripMoment.OffRoute -> base.copy(kind = "off_route")
            }
        }
    }
}
