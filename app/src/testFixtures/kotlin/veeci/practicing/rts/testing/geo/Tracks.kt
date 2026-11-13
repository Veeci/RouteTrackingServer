package veeci.practicing.rts.testing.geo

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.shared.geo.MeasuredPolyline
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private const val METRES_PER_DEGREE = Haversine.EARTH_RADIUS_M * Math.PI / 180

/** This point moved [northM] metres north and [eastM] metres east, in a flat frame (good for city distances). */
fun GeoPoint.offset(
    northM: Double = 0.0,
    eastM: Double = 0.0,
) = GeoPoint(lat + northM / METRES_PER_DEGREE, lng + eastM / (METRES_PER_DEGREE * cos(Math.toRadians(lat))))

/** A polyline from [start] that drives the given legs, each a pair (metres north, metres east). */
fun polylineFrom(
    start: GeoPoint,
    vararg legs: Pair<Double, Double>,
): MeasuredPolyline = MeasuredPolyline(legs.runningFold(start) { p, (north, east) -> p.offset(north, east) })

/** One fix of a synthetic drive. [alongM] is where the generator put it, so a test knows the right answer. */
data class TrackFix(
    val point: GeoPoint,
    val at: Instant,
    val speedMps: Double,
    val bearingDeg: Double,
    val alongM: Double,
)

/**
 * A drive along [along] at [speedMps], one fix every [stepS] seconds from [start] to the end. Each fix is moved
 * sideways by Gaussian noise with standard deviation [noiseM]; [seed] makes the noise repeatable.
 */
fun track(
    along: MeasuredPolyline,
    speedMps: Double,
    stepS: Double = 1.0,
    noiseM: Double = 0.0,
    seed: Long = 1,
    start: Instant = Instant.parse("2026-09-30T02:00:00Z"),
): List<TrackFix> {
    val random = Random(seed)
    val steps = (along.lengthM / (speedMps * stepS)).toInt()
    return (0..steps).map { i ->
        val d = i * speedMps * stepS
        val heading = headingRad(along, d)
        val side = if (noiseM == 0.0) 0.0 else random.nextGaussian() * noiseM
        // Sideways is 90° clockwise from the heading: east of a road heading north.
        val point = along.pointAt(d).offset(northM = -sin(heading) * side, eastM = cos(heading) * side)
        TrackFix(point, start + (i * stepS * 1000).toLong().milliseconds, speedMps, (Math.toDegrees(heading) + 360) % 360, d)
    }
}

/** The direction of travel at [d] metres along, in radians from north, clockwise. */
private fun headingRad(
    along: MeasuredPolyline,
    d: Double,
): Double {
    val a = along.pointAt(d - 0.5)
    val b = along.pointAt(d + 0.5)
    val east = (b.lng - a.lng) * cos(Math.toRadians(a.lat))
    return atan2(east, b.lat - a.lat)
}

/** A standard normal sample (Box-Muller). */
private fun Random.nextGaussian(): Double = kotlin.math.sqrt(-2 * kotlin.math.ln(1 - nextDouble())) * cos(2 * Math.PI * nextDouble())
