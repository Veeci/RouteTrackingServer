package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.shared.geo.Meters
import kotlin.math.PI
import kotlin.time.Instant

/** Test data for the tracking domain: positions around Ben Thanh Market, times around [NOW]. */
internal object Readings {
    val NOW: Instant = Instant.parse("2026-10-06T08:00:00Z")
    val START = GeoPoint(10.7725, 106.6980)

    /** One degree of latitude on the sphere that Haversine uses, about 111 km. */
    private val METERS_PER_DEGREE = Haversine.EARTH_RADIUS_M * PI / 180

    fun northOf(
        point: GeoPoint,
        meters: Double,
    ) = GeoPoint(point.lat + meters / METERS_PER_DEGREE, point.lng)

    /** A valid, accurate GMS fix. Use `copy` to change a field that has no parameter here. */
    fun reading(
        at: Instant = NOW,
        point: GeoPoint = START,
        accuracyM: Double = 5.0,
        mock: Boolean = false,
        satellites: SatelliteHealth? = null,
    ) = FixReading(
        lat = point.lat,
        lng = point.lng,
        accuracyM = accuracyM,
        speedMps = null,
        speedAccuracyMps = null,
        bearingDeg = null,
        bearingAccuracyDeg = null,
        altitudeM = null,
        verticalAccuracyM = null,
        recordedAt = at,
        provider = LocationProvider.GMS_FUSED,
        mock = mock,
        satellites = satellites,
    )

    /** A fix accepted in an earlier batch, as the repository would return it. */
    fun previouslyAccepted(
        reading: FixReading,
        cumulativeM: Double = 0.0,
    ) = AcceptedFix(reading.validate()!!, index = 0, Meters(0.0), Meters(cumulativeM), derivedSpeedMps = null)
}
