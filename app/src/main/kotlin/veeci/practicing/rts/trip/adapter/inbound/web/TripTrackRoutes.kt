package veeci.practicing.rts.trip.adapter.inbound.web

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.trip.adapter.inbound.web.export.GeoJsonWriter
import veeci.practicing.rts.trip.adapter.inbound.web.export.GpxWriter
import veeci.practicing.rts.trip.application.TrackExport
import veeci.practicing.rts.trip.application.TripTrack
import veeci.practicing.rts.trip.application.TripTrackService
import veeci.practicing.rts.trip.domain.TimelineEntry
import veeci.practicing.rts.trip.domain.TripLeg
import veeci.practicing.rts.trip.domain.event.TripMoment
import java.io.OutputStream
import kotlin.time.Instant

/**
 * The recorded track and timeline of an ended trip (phase 8c), and its file exports (phase 8d). Contract in
 * openapi/rts-v1.yaml. Mount inside `authenticate(AUTH_JWT)`. The service decides who may read which leg.
 */
fun Route.tripTrackRoutes(service: TripTrackService) {
    get("/api/v1/trips/{id}/track") {
        val parameters = call.request.queryParameters
        val query = TrackQuery(parameters.leg(), parameters.simplifyM())
        call.respond(TripTrackResponse.of(service.track(call.tripId(), call.actor(), query.leg, query.simplifyM)))
    }

    get("/api/v1/trips/{id}/export.gpx") {
        call.respondExport(service, "gpx", GpxWriter.CONTENT_TYPE, GpxWriter::write)
    }

    get("/api/v1/trips/{id}/export.geojson") {
        call.respondExport(service, "geojson", GeoJsonWriter.CONTENT_TYPE, GeoJsonWriter::write)
    }
}

/**
 * Every check runs in [TripTrackService.export], before the first byte: once the stream opens, the status and
 * headers are sent, and a later failure can only cut the connection.
 */
private suspend fun ApplicationCall.respondExport(
    service: TripTrackService,
    extension: String,
    contentType: String,
    write: suspend (TrackExport, OutputStream) -> Unit,
) {
    val export = service.export(tripId(), actor(), request.queryParameters.leg())
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment
            .withParameter(
                ContentDisposition.Parameters.FileName,
                "trip-${export.tripId.value}.$extension",
            ).toString(),
    )
    respondOutputStream(ContentType.parse(contentType)) { write(export, this) }
}

private data class TrackQuery(
    val leg: TripLeg,
    val simplifyM: Double,
)

/** `?leg=ride|approach`, ride when absent. */
private fun Parameters.leg(): TripLeg {
    val text = this["leg"] ?: return TripLeg.RIDE
    return TripLeg.entries.firstOrNull { it.name.lowercase() == text }
        ?: throw RequestInvalidException(listOf(FieldError("leg", "must be ride or approach")))
}

/** `?simplify=` metres from 0 to 50, 0 when absent. */
private fun Parameters.simplifyM(): Double {
    val text = this["simplify"] ?: return 0.0
    return text.toDoubleOrNull()?.takeIf { it in 0.0..TripTrackService.MAX_SIMPLIFY_M }
        ?: throw RequestInvalidException(
            listOf(FieldError("simplify", "must be a number of metres from 0 to ${TripTrackService.MAX_SIMPLIFY_M.toInt()}")),
        )
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
