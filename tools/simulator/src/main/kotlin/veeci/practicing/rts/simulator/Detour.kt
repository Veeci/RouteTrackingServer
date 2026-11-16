package veeci.practicing.rts.simulator

import kotlin.math.cos
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `--detour-at S:D:M`: from [at] after the route's start, for [length], the phone is [offsetM] metres east of the
 * recorded route, as if the driver took a parallel street. The offset grows over the first [ramp] and shrinks
 * over the last, so the server sees a drive, not a jump (it rejects jumps faster than 70 m/s).
 */
class Detour(
    val at: Duration,
    val length: Duration,
    val offsetM: Double,
    private val ramp: Duration = 10.seconds,
) {
    fun applyTo(points: List<TrackPoint>): List<TrackPoint> {
        val start = points.firstOrNull()?.time ?: return points
        val edge = minOf(ramp, length / 2)
        return points.map { point ->
            val t = point.time - start - at
            if (t < Duration.ZERO || t > length) return@map point
            val share = minOf(1.0, t / edge, (length - t) / edge)
            val metresPerDegreeLng = METRES_PER_DEGREE * cos(Math.toRadians(point.lat))
            point.copy(lng = point.lng + share * offsetM / metresPerDegreeLng)
        }
    }

    companion object {
        private const val METRES_PER_DEGREE = 111_195.0

        /** `S:D:M` in seconds, seconds and metres, for example `60:40:120`. */
        fun parse(value: String): Detour {
            val (at, length, offset) = value.split(":").map { it.trim() }
            return Detour(at.toLong().seconds, length.toLong().seconds, offset.toDouble())
        }
    }
}
