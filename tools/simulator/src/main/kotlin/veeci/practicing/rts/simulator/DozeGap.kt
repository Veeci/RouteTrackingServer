package veeci.practicing.rts.simulator

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `--doze-gap S:D`: at [at] after the route's start, the phone lies still for [length] and sends nothing, as in
 * Doze. Every later point moves [length] later and keeps its position, because a still phone does not move. The
 * status samples near the gap say STILL, so the server can flag the gap as likely Doze.
 */
class DozeGap(
    val at: Duration,
    val length: Duration,
) {
    fun applyTo(points: List<TrackPoint>): List<TrackPoint> {
        val start = points.firstOrNull()?.time ?: return points
        return points.map { if (it.time - start >= at) it.copy(time = it.time + length) else it }
    }

    /**
     * Whether a status at [time] falls near the gap of a route that starts at [routeStart]. The window reaches
     * [MARGIN] past each side: a status goes out only every 30 s, before a batch, so the last one before the gap
     * and the first one after it may be that far from its edges.
     */
    fun isStillAt(
        time: Instant,
        routeStart: Instant,
    ): Boolean = (time - routeStart) in (at - MARGIN)..(at + length + MARGIN)

    companion object {
        private val MARGIN = 1.minutes

        /** Parses `<route seconds>:<seconds>`, for example `60:360`. */
        fun parse(value: String): DozeGap {
            val (at, length) = value.split(":").map { it.trim().toLong() }
            return DozeGap(at.seconds, length.seconds)
        }
    }
}
