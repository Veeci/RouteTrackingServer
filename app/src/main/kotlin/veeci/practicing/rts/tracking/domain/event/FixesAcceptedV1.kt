package veeci.practicing.rts.tracking.domain.event

import kotlinx.serialization.Serializable
import veeci.practicing.rts.shared.geo.GeoPoint
import java.util.UUID
import kotlin.time.Instant

/**
 * [FixesAccepted] as JSON in the outbox, under the event type [TYPE]. Readers in other contexts decode this
 * class, so it is a contract: fields may be added (readers ignore unknown ones), but a change to an existing
 * field needs a new type, `...v2`.
 */
@Serializable
data class FixesAcceptedV1(
    val sessionId: String,
    val deviceId: String,
    val driverId: String? = null,
    val seq: Long,
    val positions: List<Position>,
) {
    @Serializable
    data class Position(
        val lat: Double,
        val lng: Double,
        val recordedAt: Instant,
        val speedMps: Double? = null,
        val bearingDeg: Double? = null,
        val cumulativeM: Double,
    )

    fun toEvent() =
        FixesAccepted(
            sessionId = UUID.fromString(sessionId),
            deviceId = deviceId,
            driverId = driverId?.let(UUID::fromString),
            seq = seq,
            positions =
                positions.map {
                    FixesAccepted.Position(
                        GeoPoint(it.lat, it.lng),
                        it.recordedAt,
                        it.speedMps,
                        it.bearingDeg,
                        it.cumulativeM,
                    )
                },
        )

    companion object {
        const val TYPE = "tracking.FixesAccepted.v1"

        fun of(event: FixesAccepted) =
            FixesAcceptedV1(
                sessionId = event.sessionId.toString(),
                deviceId = event.deviceId,
                driverId = event.driverId?.toString(),
                seq = event.seq,
                positions =
                    event.positions.map {
                        Position(it.point.lat, it.point.lng, it.recordedAt, it.speedMps, it.bearingDeg, it.cumulativeM)
                    },
            )
    }
}
