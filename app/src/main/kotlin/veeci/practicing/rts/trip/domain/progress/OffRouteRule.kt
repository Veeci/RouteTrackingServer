package veeci.practicing.rts.trip.domain.progress

data class OffRouteConfig(
    /** A fix farther than this beside the route counts toward off-route. */
    val enterBeyondM: Double = 50.0,
    /** A fix this close to the route resets the count. Between the two the count stays as it was (hysteresis). */
    val resetWithinM: Double = 30.0,
    /** A fix less precise than this never counts: it may be in a tunnel or between tall buildings. */
    val maxAccuracyM: Double = 30.0,
    /** This many counting fixes in a row make the driver off-route. */
    val fixes: Int = 3,
)

/**
 * Decides, one fix at a time, whether the driver has left the route.
 *
 * Two thresholds, like the arriving rule: the count goes up only beyond [OffRouteConfig.enterBeyondM] and resets
 * only within [OffRouteConfig.resetWithinM]. GPS noise around one line would otherwise make the state flip on
 * every fix. A fix of unknown accuracy (an event stored before phase 7b) never counts.
 */
class OffRouteRule(
    private val config: OffRouteConfig = OffRouteConfig(),
) {
    /** The count after a fix [crossTrackM] beside the route, measured with [accuracyM]. */
    fun count(
        previous: Int,
        crossTrackM: Double,
        accuracyM: Double?,
    ): Int =
        when {
            crossTrackM <= config.resetWithinM -> 0
            crossTrackM > config.enterBeyondM && accuracyM != null && accuracyM <= config.maxAccuracyM -> previous + 1
            else -> previous
        }

    fun isOffRoute(count: Int): Boolean = count >= config.fixes
}
