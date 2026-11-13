package veeci.practicing.rts.trip.domain.progress

/**
 * The ETA along a planned route: the route's planned time for the distance left, scaled by how fast the driver
 * really drives compared with the plan.
 *
 * `eta = durationS × remainingM ÷ distanceM × k`, where `k = planned speed ÷ observed speed`, clamped to
 * [[minFactor], [maxFactor]]. The observed speed is the progress service's smoothed speed, so k moves slowly.
 *
 * The route stores only its total time, so the planned speed is the route's average. Without the clamp this ETA
 * would be "distance left ÷ observed speed". The clamp is what the plan adds: a driver stopped at a red light makes
 * the ETA at most [maxFactor] times the plan, not endless, and a driver racing makes it at least [minFactor] times.
 */
class RouteEta(
    private val minFactor: Double = MIN_FACTOR,
    private val maxFactor: Double = MAX_FACTOR,
) {
    /** The correction factor k. Without an observed speed yet, the plan counts as right (k = 1). */
    fun factor(
        plannedSpeedMps: Double,
        observedSpeedMps: Double?,
    ): Double =
        when {
            observedSpeedMps == null -> 1.0
            observedSpeedMps <= 0.0 -> maxFactor
            else -> (plannedSpeedMps / observedSpeedMps).coerceIn(minFactor, maxFactor)
        }

    fun etaS(
        routeDistanceM: Double,
        routeDurationS: Double,
        remainingM: Double,
        observedSpeedMps: Double?,
    ): Double {
        if (routeDistanceM <= 0.0 || routeDurationS <= 0.0) return 0.0
        val plannedRemainingS = routeDurationS * remainingM / routeDistanceM
        return plannedRemainingS * factor(routeDistanceM / routeDurationS, observedSpeedMps)
    }

    private companion object {
        const val MIN_FACTOR = 0.5
        const val MAX_FACTOR = 3.0
    }
}
