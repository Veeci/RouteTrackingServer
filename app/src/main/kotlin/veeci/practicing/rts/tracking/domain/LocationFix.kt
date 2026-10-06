package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Meters
import kotlin.time.Instant

/** A [FixReading] that passed validation: every value is finite and inside its range. */
data class LocationFix(
    val point: GeoPoint,
    val accuracy: Meters,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val bearingDeg: Double?,
    val bearingAccuracyDeg: Double?,
    val altitudeM: Double?,
    val verticalAccuracyM: Double?,
    val recordedAt: Instant,
    val provider: LocationProvider,
    val mock: Boolean,
    val satellites: SatelliteHealth?,
)

/** A fix that passed every rule, with the values the server computed from the fix before it. */
data class AcceptedFix(
    val fix: LocationFix,
    /** Position of the fix in its batch, as the client sent it. */
    val index: Int,
    /** 0 for the first fix of a session. */
    val distanceFromPrev: Meters,
    /** Distance travelled since the session started, up to this fix. */
    val cumulative: Meters,
    /** Speed between the previous accepted fix and this one; null for the first fix of a session. */
    val derivedSpeedMps: Double?,
)

data class Rejection(
    val index: Int,
    val reason: RejectionReason,
)

/** Why a fix was dropped. The names travel to the client in `ack.rejected[].reason`, so they are never renamed. */
enum class RejectionReason {
    INVALID_VALUE,
    MOCK_LOCATION,
    POOR_ACCURACY,
    FUTURE_TIMESTAMP,
    TOO_OLD,
    DUPLICATE_TIMESTAMP,
    IMPLAUSIBLE_JUMP,
}
