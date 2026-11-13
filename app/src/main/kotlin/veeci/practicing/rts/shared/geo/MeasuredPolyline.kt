package veeci.practicing.rts.shared.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A polyline that knows how far along it each of its points lies, so a position can be turned into "metres from the
 * start" and "metres beside the line".
 *
 * Segment `i` runs from `points[i]` to `points[i + 1]`. The distance from the start to each point (`cumulativeM`) is
 * added up once, in the constructor, so a projection costs one segment's arithmetic plus one array read.
 *
 * Projection works in a flat local frame around each segment (equirectangular): x = Δlng · cos(lat) · R and
 * y = Δlat · R, in metres. Over a city segment of a few hundred metres the error against the sphere is well under
 * a metre, and it needs no trigonometry per point except one cosine.
 */
class MeasuredPolyline(
    points: List<GeoPoint>,
) {
    /** At least two points: a single point becomes a segment of length zero, so every polyline has a segment. */
    val points: List<GeoPoint> = if (points.size == 1) points + points else points.toList()

    private val cumulativeM = DoubleArray(this.points.size)

    init {
        require(points.isNotEmpty()) { "A polyline needs at least one point" }
        for (i in 1 until this.points.size) {
            cumulativeM[i] = cumulativeM[i - 1] + Haversine.distance(this.points[i - 1], this.points[i]).value
        }
    }

    val segmentCount: Int get() = points.size - 1

    val lengthM: Double get() = cumulativeM.last()

    /** The distance from the start to point [index] along the line. */
    fun distanceToPointM(index: Int): Double = cumulativeM[index]

    /** The point [alongM] metres from the start, along the line; clamped to the two ends. */
    fun pointAt(alongM: Double): GeoPoint {
        val d = alongM.coerceIn(0.0, lengthM)
        // The last point whose distance is at most d starts the segment that holds d.
        val found = cumulativeM.binarySearch(d)
        val index = (if (found >= 0) found else -found - 2).coerceIn(0, segmentCount - 1)
        val segmentM = cumulativeM[index + 1] - cumulativeM[index]
        val t = if (segmentM == 0.0) 0.0 else (d - cumulativeM[index]) / segmentM
        val a = points[index]
        val b = points[index + 1]
        return GeoPoint(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
    }

    /** The nearest point of the segments in [segments] to [p]; on a tie the earlier segment wins. */
    fun project(
        p: GeoPoint,
        segments: IntRange = 0 until segmentCount,
    ): Projection {
        val first = segments.first.coerceIn(0, segmentCount - 1)
        val last = segments.last.coerceIn(first, segmentCount - 1)
        var best = projectOnSegment(p, first)
        for (i in first + 1..last) {
            val candidate = projectOnSegment(p, i)
            if (candidate.crossTrackM < best.crossTrackM) best = candidate
        }
        return best
    }

    /** [p] projected on segment [index]: the closest point of that segment, never beyond its two ends. */
    fun projectOnSegment(
        p: GeoPoint,
        index: Int,
    ): Projection {
        require(index in 0 until segmentCount) { "Segment $index is not in 0 until $segmentCount" }
        val a = points[index]
        val b = points[index + 1]
        val metresPerDegreeLng = METRES_PER_DEGREE * cos(Math.toRadians((a.lat + b.lat) / 2))
        // a is the origin of the local frame.
        val bx = (b.lng - a.lng) * metresPerDegreeLng
        val by = (b.lat - a.lat) * METRES_PER_DEGREE
        val px = (p.lng - a.lng) * metresPerDegreeLng
        val py = (p.lat - a.lat) * METRES_PER_DEGREE
        val lengthSquared = bx * bx + by * by
        // t is where the perpendicular from p meets the line through a and b; 0 at a, 1 at b. Clamped to the segment.
        val t = if (lengthSquared == 0.0) 0.0 else ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
        val segmentM = cumulativeM[index + 1] - cumulativeM[index]
        return Projection(
            segmentIndex = index,
            fraction = t,
            point = GeoPoint(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng)),
            alongM = cumulativeM[index] + t * segmentM,
            crossTrackM = hypot(px - t * bx, py - t * by),
            segmentBearingDeg = if (lengthSquared == 0.0) null else bearingDeg(bx, by),
        )
    }

    private fun bearingDeg(
        east: Double,
        north: Double,
    ): Double = (Math.toDegrees(atan2(east, north)) + FULL_TURN_DEG) % FULL_TURN_DEG

    private companion object {
        /** The length of one degree of latitude on the sphere [Haversine] uses. */
        const val METRES_PER_DEGREE = Haversine.EARTH_RADIUS_M * Math.PI / 180
        const val FULL_TURN_DEG = 360.0
    }
}

/** Where a position lands on a [MeasuredPolyline]. */
data class Projection(
    val segmentIndex: Int,
    /** 0 at the segment's start, 1 at its end. */
    val fraction: Double,
    /** The closest point on the line. */
    val point: GeoPoint,
    /** Metres from the polyline's start to [point], along the line. */
    val alongM: Double,
    /** Metres from the position to [point]: how far beside the line the position is. */
    val crossTrackM: Double,
    /** The segment's direction, 0 = north, clockwise. Null for a segment of length zero. */
    val segmentBearingDeg: Double?,
)
