package veeci.practicing.rts.trip.domain.progress

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.MeasuredPolyline
import veeci.practicing.rts.shared.geo.Projection
import kotlin.math.abs

data class MatchConfig(
    /** Segments behind the cursor that may still match: the driver can be a little behind the last match. */
    val segmentsBack: Int = 2,
    /** Segments ahead of the cursor that may match. A later part of a looping route lies outside this window. */
    val segmentsAhead: Int = 25,
    /** Added to a candidate's cross-track when the driver drives against the segment's direction. */
    val bearingPenaltyM: Double = 30.0,
    /** A heading that differs from the segment's by more than this counts as "against the segment". */
    val bearingToleranceDeg: Double = 60.0,
    /** Below this speed the device's bearing is noise, so it is not compared. */
    val bearingMinSpeedMps: Double = 3.0,
    /** A step back along the route up to this far is GPS jitter; the distance driven does not shrink for it. */
    val jitterM: Double = 20.0,
    /**
     * When the best match in the window is farther than this from the driver, the rest of the route ahead is searched
     * too. It finds the driver again after a gap (a tunnel) longer than the window.
     */
    val searchAheadBeyondM: Double = 50.0,
)

/** One fix, as the matcher sees it. */
data class MatchInput(
    val point: GeoPoint,
    val speedMps: Double?,
    val bearingDeg: Double?,
)

/** What the matcher remembers between two fixes on the same route. */
data class MatchState(
    /** The segment of the last match. The next search window is built around it. */
    val cursor: Int,
    /** The furthest distance along the route that counted. */
    val alongM: Double,
)

data class RouteMatch(
    val state: MatchState,
    val projection: Projection,
    /** Distance driven along the route, after the jitter guard. */
    val alongM: Double,
    val remainingM: Double,
)

/**
 * Snaps the driver's fixes onto a planned route, one fix at a time.
 *
 * The search is a window around the last match, not the whole polyline. A route that comes back past the same
 * corner (a loop, a U-turn road) would otherwise let the driver jump to the later pass and lose a kilometre of
 * distance in one fix. A fix that matches nothing near in the window searches the rest of the route ahead.
 * The window counts segments, not metres, so it is shorter where the route has many short segments.
 *
 * A candidate's score is its cross-track distance, plus [MatchConfig.bearingPenaltyM] when the driver moves against
 * the segment. On a two-way street mapped as two parallel lines, this picks the side the driver is on.
 */
class RouteMatcher(
    private val config: MatchConfig = MatchConfig(),
) {
    /**
     * Matches [fix] on [route]. Without a [state] (a new route, or a restart) the window starts at the route's start:
     * a new route is planned from where the driver is. After a restart mid-route, nothing near matches there, so the
     * search goes on along the rest of the route.
     */
    fun match(
        route: MeasuredPolyline,
        state: MatchState?,
        fix: MatchInput,
    ): RouteMatch {
        val last = route.segmentCount - 1
        val cursor = state?.cursor?.coerceIn(0, last) ?: 0
        val window = (cursor - config.segmentsBack).coerceAtLeast(0)..(cursor + config.segmentsAhead).coerceAtMost(last)
        var best = bestIn(route, window, fix)
        if (best.projection.crossTrackM > config.searchAheadBeyondM && window.last < last) {
            val ahead = bestIn(route, window.last + 1..last, fix)
            if (ahead.score < best.score) best = ahead
        }
        val projection = best.projection
        val alongM = guardJitter(projection.alongM, state?.alongM)
        return RouteMatch(MatchState(projection.segmentIndex, alongM), projection, alongM, (route.lengthM - alongM).coerceAtLeast(0.0))
    }

    /**
     * A small step back (GPS jitter) keeps the previous distance, so the distance left never grows from noise.
     * A larger step back is real: the driver turned around.
     */
    private fun guardJitter(
        alongM: Double,
        previousM: Double?,
    ): Double =
        when {
            previousM == null -> alongM
            alongM >= previousM -> alongM
            alongM >= previousM - config.jitterM -> previousM
            else -> alongM
        }

    private fun bestIn(
        route: MeasuredPolyline,
        segments: IntRange,
        fix: MatchInput,
    ): Candidate {
        var best: Candidate? = null
        for (i in segments) {
            val projection = route.projectOnSegment(fix.point, i)
            val candidate = Candidate(projection, projection.crossTrackM + penalty(projection, fix))
            if (best == null || candidate.score < best.score) best = candidate // strict: on a tie the earlier one stays
        }
        return checkNotNull(best) { "No segment in $segments" }
    }

    private fun penalty(
        projection: Projection,
        fix: MatchInput,
    ): Double {
        val segment = projection.segmentBearingDeg
        val heading = fix.bearingDeg
        // Below the minimum speed the device's bearing is noise; a segment of length zero has no direction.
        val comparable = segment != null && heading != null && (fix.speedMps ?: 0.0) > config.bearingMinSpeedMps
        val against = comparable && angleBetween(segment, heading) > config.bearingToleranceDeg
        return if (against) config.bearingPenaltyM else 0.0
    }

    private class Candidate(
        val projection: Projection,
        val score: Double,
    )

    private companion object {
        const val FULL_TURN_DEG = 360.0
        const val HALF_TURN_DEG = 180.0

        /** The smaller angle between two bearings, 0 to 180 degrees. */
        fun angleBetween(
            a: Double,
            b: Double,
        ): Double {
            val d = abs(a - b) % FULL_TURN_DEG
            return if (d > HALF_TURN_DEG) FULL_TURN_DEG - d else d
        }
    }
}
