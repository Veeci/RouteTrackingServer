package veeci.practicing.rts.live.adapter.out.bus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import veeci.practicing.rts.live.application.api.DriverPositionUpdate
import veeci.practicing.rts.live.application.api.LiveUpdate
import veeci.practicing.rts.live.application.api.RouteUpdate
import veeci.practicing.rts.live.application.api.TripMomentUpdate
import veeci.practicing.rts.live.application.api.TripProgressUpdate
import veeci.practicing.rts.live.application.api.TripStatusUpdate
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Instant

/**
 * A [LiveUpdate] as JSON between instances (phase 10). Every instance runs the same release during a rolling
 * deploy only briefly, so fields may be added with defaults, never renamed: an old instance must read a new one.
 */
internal object LiveUpdateWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(update: LiveUpdate): String = json.encodeToString(Wire.serializer(), Wire.of(update))

    fun decode(text: String): LiveUpdate = json.decodeFromString(Wire.serializer(), text).toUpdate()

    @Serializable
    private sealed interface Wire {
        fun toUpdate(): LiveUpdate

        @Serializable
        @SerialName("position")
        data class Position(
            val tripId: String,
            val lat: Double,
            val lng: Double,
            val bearingDeg: Double? = null,
            val speedMps: Double? = null,
            val recordedAt: Instant,
        ) : Wire {
            override fun toUpdate() = DriverPositionUpdate(UUID.fromString(tripId), GeoPoint(lat, lng), bearingDeg, speedMps, recordedAt)
        }

        @Serializable
        @SerialName("progress")
        data class Progress(
            val tripId: String,
            val target: String,
            val remainingM: Double,
            val etaS: Double,
            val basis: String,
            val routeVersion: Int? = null,
        ) : Wire {
            override fun toUpdate() = TripProgressUpdate(UUID.fromString(tripId), target, remainingM, etaS, basis, routeVersion)
        }

        @Serializable
        @SerialName("status")
        data class Status(
            val tripId: String,
            val status: TripStatus,
            val at: Instant,
        ) : Wire {
            override fun toUpdate() = TripStatusUpdate(UUID.fromString(tripId), status, at)
        }

        @Serializable
        @SerialName("route")
        data class Route(
            val tripId: String,
            val kind: String,
            val version: Int,
            val polyline: String,
            val distanceM: Double,
            val durationS: Double,
            val reason: String,
        ) : Wire {
            override fun toUpdate() = RouteUpdate(UUID.fromString(tripId), kind, version, polyline, distanceM, durationS, reason)
        }

        @Serializable
        @SerialName("moment")
        data class Moment(
            val tripId: String,
            val kind: String,
            val status: TripStatus,
            val at: Instant,
            val remainingM: Double? = null,
            val levelPercent: Int? = null,
            val lastSeenAt: Instant? = null,
        ) : Wire {
            override fun toUpdate() = TripMomentUpdate(UUID.fromString(tripId), kind, status, at, remainingM, levelPercent, lastSeenAt)
        }

        companion object {
            fun of(update: LiveUpdate): Wire =
                when (update) {
                    is DriverPositionUpdate -> {
                        Position(
                            update.tripId.toString(),
                            update.point.lat,
                            update.point.lng,
                            update.bearingDeg,
                            update.speedMps,
                            update.recordedAt,
                        )
                    }

                    is TripProgressUpdate -> {
                        Progress(update.tripId.toString(), update.target, update.remainingM, update.etaS, update.basis, update.routeVersion)
                    }

                    is TripStatusUpdate -> {
                        Status(update.tripId.toString(), update.status, update.at)
                    }

                    is RouteUpdate -> {
                        Route(
                            update.tripId.toString(),
                            update.kind,
                            update.version,
                            update.polyline,
                            update.distanceM,
                            update.durationS,
                            update.reason,
                        )
                    }

                    is TripMomentUpdate -> {
                        Moment(
                            update.tripId.toString(),
                            update.kind,
                            update.status,
                            update.at,
                            update.remainingM,
                            update.levelPercent,
                            update.lastSeenAt,
                        )
                    }
                }
        }
    }
}
