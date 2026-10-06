package veeci.practicing.rts.tracking.domain

import kotlin.time.Instant

/**
 * One position exactly as the device reported it, before any check. The pipeline turns it into a [LocationFix]
 * or rejects it with [RejectionReason.INVALID_VALUE].
 */
data class FixReading(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
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

/** The location stack that produced a fix. */
enum class LocationProvider { GMS_FUSED, AOSP_GPS, AOSP_NETWORK, AOSP_FUSED, UNKNOWN }

data class SatelliteHealth(
    val usedInFix: Int,
    val meanCn0DbHz: Double?,
)
