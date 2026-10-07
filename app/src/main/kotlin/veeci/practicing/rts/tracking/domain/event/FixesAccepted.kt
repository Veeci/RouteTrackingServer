package veeci.practicing.rts.tracking.domain.event

import veeci.practicing.rts.shared.geo.GeoPoint
import java.util.UUID
import kotlin.time.Instant

/**
 * A stored batch had accepted fixes. Other contexts (trip, telemetry) react to it, so it is part of the
 * tracking context's public API: it uses only plain and shared-kernel types, never tracking's own model.
 */
data class FixesAccepted(
    val sessionId: UUID,
    val deviceId: String,
    val seq: Long,
    /** In time order. */
    val positions: List<Position>,
) {
    data class Position(
        val point: GeoPoint,
        val recordedAt: Instant,
        val speedMps: Double?,
        val bearingDeg: Double?,
        /** Distance travelled in the session up to this position. */
        val cumulativeM: Double,
    )
}
