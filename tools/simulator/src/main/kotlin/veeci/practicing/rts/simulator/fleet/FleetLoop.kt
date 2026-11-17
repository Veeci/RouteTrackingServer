package veeci.practicing.rts.simulator.fleet

import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.simulator.TrackPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * One simulated driver's route: a recorded loop, moved by [northM] and [eastM] so that the fleet spreads over the city,
 * started at point [start]. Each call to [next] returns the next [batchSize] points as fixes [fixEvery] apart, the last
 * one recorded at `now`. The loop wraps around without end.
 */
class FleetLoop(
    points: List<TrackPoint>,
    private val start: Int,
    northM: Double,
    eastM: Double,
    private val fixEvery: Duration,
    private val batchSize: Int,
) {
    // A loop's last point repeats its first; sending both would be a fix that does not move.
    private val loop =
        points
            .let { if (it.size > 1 && it.first().lat == it.last().lat && it.first().lng == it.last().lng) it.dropLast(1) else it }
            .map { it.shifted(northM, eastM) }
    private var index = start % loop.size
    private var last: Instant? = null

    init {
        require(loop.isNotEmpty()) { "The route has no points" }
        require(batchSize > 0) { "A batch needs at least one fix" }
    }

    /** The next batch, recorded up to [now]. Times only go forward, even when the caller's clock does not. */
    fun next(now: Instant): List<FixDto> =
        (0 until batchSize)
            .map { k ->
                val point = loop[(index + k) % loop.size]
                val wanted = now - fixEvery * (batchSize - 1 - k)
                val at = last?.let { maxOf(wanted, it + 1.milliseconds) } ?: wanted
                last = at
                FixDto(point.first, point.second, accuracyM = ACCURACY_M, recordedAt = at)
            }.also { index = (index + batchSize) % loop.size }

    private fun TrackPoint.shifted(
        northM: Double,
        eastM: Double,
    ): Pair<Double, Double> = (lat + northM / M_PER_DEG) to (lng + eastM / (M_PER_DEG * cos(lat * PI / DEG_180)))

    companion object {
        private const val ACCURACY_M = 8.0
        private const val M_PER_DEG = 111_320.0
        private const val DEG_180 = 180.0

        /** A loop for driver number [n]: a random start point and a random shift of up to [spreadM] in each direction. */
        fun random(
            points: List<TrackPoint>,
            spreadM: Double,
            fixEvery: Duration,
            batchSize: Int,
            random: Random = Random.Default,
        ) = FleetLoop(
            points,
            start = random.nextInt(points.size),
            northM = random.nextDouble(-spreadM, spreadM),
            eastM = random.nextDouble(-spreadM, spreadM),
            fixEvery = fixEvery,
            batchSize = batchSize,
        )
    }
}
