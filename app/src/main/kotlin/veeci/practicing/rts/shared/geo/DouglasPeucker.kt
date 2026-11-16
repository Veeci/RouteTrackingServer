package veeci.practicing.rts.shared.geo

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Ramer–Douglas–Peucker line simplification (phase 8c). It keeps the two ends, finds the point farthest from the
 * line between them, and keeps that point when it is farther than the tolerance; then it does the same for the
 * two halves. Every dropped point lies within the tolerance of the simplified line.
 *
 * Distances are measured in a flat local frame around the first point (equirectangular, as in
 * [MeasuredPolyline]): x = Δlng · cos(lat₀) · R, y = Δlat · R, in metres. Over a city-sized track the error is a
 * fraction of a percent of the measured distance, far below the tolerances used here. Tracks that cross the
 * antimeridian are not handled.
 *
 * The halves wait on an explicit stack instead of the call stack, so a long track (100 000 points or more) cannot
 * overflow it. The work is O(n log n) on typical tracks and O(n²) at worst.
 */
object DouglasPeucker {
    fun simplify(
        points: List<GeoPoint>,
        toleranceM: Double,
    ): List<GeoPoint> = simplify(points, toleranceM) { it }

    /** Simplifies [points], whatever they carry, by the position [at] gives; the kept items come back unchanged. */
    fun <T> simplify(
        points: List<T>,
        toleranceM: Double,
        at: (T) -> GeoPoint,
    ): List<T> {
        require(toleranceM >= 0) { "toleranceM must not be negative, was $toleranceM" }
        if (points.size <= 2 || toleranceM == 0.0) return points
        val keep = keptIndices(points.map(at), toleranceM)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun keptIndices(
        positions: List<GeoPoint>,
        toleranceM: Double,
    ): BooleanArray {
        val origin = positions.first()
        val metresPerDegreeLng = METRES_PER_DEGREE * cos(Math.toRadians(origin.lat))
        val x = DoubleArray(positions.size) { (positions[it].lng - origin.lng) * metresPerDegreeLng }
        val y = DoubleArray(positions.size) { (positions[it].lat - origin.lat) * METRES_PER_DEGREE }

        val keep = BooleanArray(positions.size)
        keep[0] = true
        keep[positions.lastIndex] = true
        // Each entry is a range (first, last) whose ends are kept and whose inside is not decided yet.
        val ranges = ArrayDeque<Long>()
        ranges.addLast(range(0, positions.lastIndex))
        while (ranges.isNotEmpty()) {
            val packed = ranges.removeLast()
            val first = (packed ushr Int.SIZE_BITS).toInt()
            val last = packed.toInt()
            var farthest = -1
            var farthestM = toleranceM
            for (i in first + 1 until last) {
                val d = distanceToSegmentM(x[i], y[i], x[first], y[first], x[last], y[last])
                if (d > farthestM) {
                    farthest = i
                    farthestM = d
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                if (farthest - first > 1) ranges.addLast(range(first, farthest))
                if (last - farthest > 1) ranges.addLast(range(farthest, last))
            }
        }
        return keep
    }

    /** Two indexes in one Long, so the stack holds no objects. */
    private fun range(
        first: Int,
        last: Int,
    ): Long = (first.toLong() shl Int.SIZE_BITS) or last.toLong()

    /** The distance from (px, py) to the segment a–b, all in metres; the segment's ends count, not only its line. */
    @Suppress("LongParameterList") // six coordinates; a holder object per call would allocate in the hot loop
    private fun distanceToSegmentM(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private const val METRES_PER_DEGREE = Haversine.EARTH_RADIUS_M * Math.PI / 180
}
