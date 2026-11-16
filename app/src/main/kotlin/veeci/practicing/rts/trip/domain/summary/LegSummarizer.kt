package veeci.practicing.rts.trip.domain.summary

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

/** One recorded position, as the summary needs it. */
data class TrackSample(
    val point: GeoPoint,
    val at: Instant,
    /** The phone's speed, or the tracking context's derived one; null when neither is known. */
    val speedMps: Double?,
)

/**
 * Sums up a leg in one pass, so a long track can be read page by page and never held whole: [add] each sample in
 * time order, then call [result].
 *
 * - **Distance** adds the straight line between consecutive samples. A gap longer than [MAX_GAP] (the phone was
 *   offline, or the driver switched phones) adds nothing, so a reconnect never draws a line across the city.
 * - **Moving time** adds the time of each segment, outside gaps, whose speed is [MOVING_MPS] or more.
 * - **Max speed** is the highest median of three consecutive speeds. A single spike sits between two normal values
 *   and is never a median. With fewer than three speeds it is the lowest one, for the same reason.
 */
class LegSummarizer(
    private val from: Instant,
    private val to: Instant,
) {
    private var previous: TrackSample? = null
    private var distanceM = 0.0
    private var movingTime = Duration.ZERO
    private var count = 0
    private val lastSpeeds = ArrayDeque<Double>(WINDOW)
    private var maxMedian: Double? = null
    private var minSpeed: Double? = null

    fun add(sample: TrackSample) {
        val before = previous
        var segmentSpeed: Double? = null
        if (before != null) {
            val dt = sample.at - before.at
            if (dt > Duration.ZERO && dt <= MAX_GAP) {
                val metres = Haversine.distance(before.point, sample.point).value
                distanceM += metres
                segmentSpeed = metres / dt.toDouble(DurationUnit.SECONDS)
                if (segmentSpeed >= MOVING_MPS) movingTime += dt
            }
        }
        (sample.speedMps ?: segmentSpeed)?.let(::addSpeed)
        previous = sample
        count++
    }

    fun result(): LegSummary {
        val movingS = movingTime.inWholeSeconds
        return LegSummary(
            distanceM = distanceM,
            durationS = (to - from).inWholeSeconds.coerceAtLeast(0),
            movingTimeS = movingS,
            avgSpeedMps = if (movingTime > Duration.ZERO) distanceM / movingTime.toDouble(DurationUnit.SECONDS) else 0.0,
            maxSpeedMps = maxMedian ?: minSpeed ?: 0.0,
            pointCount = count,
            startedAt = from,
            endedAt = to,
        )
    }

    private fun addSpeed(speed: Double) {
        minSpeed = minOf(minSpeed ?: speed, speed)
        if (lastSpeeds.size == WINDOW) lastSpeeds.removeFirst()
        lastSpeeds.addLast(speed)
        if (lastSpeeds.size == WINDOW) {
            val median = lastSpeeds.sorted()[1]
            maxMedian = maxOf(maxMedian ?: median, median)
        }
    }

    companion object {
        val MAX_GAP: Duration = 60.seconds
        const val MOVING_MPS = 1.0
        private const val WINDOW = 3
    }
}
