package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Meters

private const val FULL_CIRCLE_DEGREES = 360.0

internal fun FixReading.validate(): LocationFix? {
    val valid =
        GeoPoint.isValid(lat, lng) && accuracyM.isFinite() &&
            accuracyM > 0 &&
            speedMps.isAbsentOrNonNegative() &&
            speedAccuracyMps.isAbsentOrNonNegative() &&
            bearingDeg.isAbsentOrBearing() &&
            bearingAccuracyDeg.isAbsentOrNonNegative() &&
            altitudeM.isAbsentOrFinite() &&
            verticalAccuracyM.isAbsentOrNonNegative() &&
            satellites.isAbsentOrValid()

    if (!valid) return null
    return LocationFix(
        point = GeoPoint(lat, lng),
        accuracy = Meters(accuracyM),
        speedMps = speedMps,
        speedAccuracyMps = speedAccuracyMps,
        bearingDeg = bearingDeg,
        bearingAccuracyDeg = bearingAccuracyDeg,
        altitudeM = altitudeM,
        verticalAccuracyM = verticalAccuracyM,
        recordedAt = recordedAt,
        provider = provider,
        mock = mock,
        satellites = satellites,
    )
}

private fun Double?.isAbsentOrFinite(): Boolean = this == null || isFinite()

private fun Double?.isAbsentOrNonNegative(): Boolean = this == null || (isFinite() && this >= 0)

private fun Double?.isAbsentOrBearing(): Boolean = this == null || (this in 0.0..<FULL_CIRCLE_DEGREES)

private fun SatelliteHealth?.isAbsentOrValid(): Boolean = this == null || (usedInFix >= 0 && meanCn0DbHz.isAbsentOrNonNegative())
