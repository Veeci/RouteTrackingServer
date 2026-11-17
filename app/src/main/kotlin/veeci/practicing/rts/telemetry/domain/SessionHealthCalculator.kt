package veeci.practicing.rts.telemetry.domain

import veeci.practicing.rts.shared.stats.Point
import veeci.practicing.rts.shared.stats.percentile
import veeci.practicing.rts.shared.stats.slope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Computes a session's [SessionHealth]. Pure: the same input always gives the same result, so every rule is
 * tested with literal timestamps.
 *
 * - A gap is an interval between two accepted fixes longer than `max(3 × expected, 30 s)`, where `expected` is the
 *   interval of the constraint in effect when the gap started.
 * - A gap is `likelyDoze` when it lasts 5 minutes or more, and the last sample at or before its start and the first
 *   sample at or after its end both say not charging and STILL. A missing sample or motion gives no flag.
 * - Battery drain is one regression slope per run of non-charging samples (2 or more samples over 10 minutes or
 *   more), then the mean of the slopes weighted by each run's length. One slope over all samples would join two
 *   runs across a charge.
 */
object SessionHealthCalculator {
    private const val GAP_FACTOR = 3
    private val MIN_GAP = 30.seconds
    private val MIN_DOZE = 5.minutes
    private val MIN_RUN = 10.minutes
    private const val STILL = "STILL"
    private const val P50 = 50.0
    private const val P95 = 95.0
    private const val MS_PER_S = 1_000.0

    fun calculate(input: HealthInput): SessionHealth {
        val fixes = input.fixes.sortedBy { it.at }
        val rejected = input.rejections.values.sum()
        val total = fixes.size + rejected
        val intervals = fixes.zipWithNext { a, b -> (b.at - a.at).inWholeMilliseconds / MS_PER_S }
        val accuracies = fixes.map { it.accuracyM }
        val mix = providerMix(fixes)
        return SessionHealth(
            fixCount = fixes.size,
            rejectedCount = rejected,
            acceptedRatio = if (total == 0) null else fixes.size.toDouble() / total,
            rejectionsByReason = input.rejections.filterValues { it > 0 }.toSortedMap(),
            intervalP50S = percentile(intervals, P50),
            intervalP95S = percentile(intervals, P95),
            accuracyP50M = percentile(accuracies, P50),
            accuracyP95M = percentile(accuracies, P95),
            gaps = gaps(fixes, input),
            batteryDrainPctPerHour = drain(input.samples),
            providerMix = mix,
            primaryProvider =
                mix.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key },
                    ).firstOrNull()
                    ?.key,
        )
    }

    private fun providerMix(fixes: List<HealthFix>): Map<String, Double> =
        fixes
            .groupingBy { it.provider }
            .eachCount()
            .mapValues { (_, count) -> count.toDouble() / fixes.size }
            .toSortedMap()

    private fun gaps(
        fixes: List<HealthFix>,
        input: HealthInput,
    ): List<Gap> {
        val samples = input.samples.sortedBy { it.at }
        val constraints = input.constraints.sortedBy { it.appliedAt }
        return fixes.zipWithNext().mapNotNull { (before, after) ->
            val expected = expectedInterval(before.at, constraints, input.defaultInterval)
            val length = after.at - before.at
            if (length <= maxOf(expected * GAP_FACTOR, MIN_GAP)) {
                null
            } else {
                Gap(
                    start = before.at,
                    durationS = length.inWholeMilliseconds / MS_PER_S,
                    expectedIntervalS = expected.inWholeMilliseconds / MS_PER_S,
                    likelyDoze = length >= MIN_DOZE && dozeLike(samples, before.at, after.at),
                )
            }
        }
    }

    /** The interval of the constraint in effect at [at]. A constraint of 0 ms means "as fast as you can": the default. */
    private fun expectedInterval(
        at: Instant,
        constraints: List<ConstraintChange>,
        default: Duration,
    ): Duration {
        val ms = constraints.lastOrNull { it.appliedAt <= at }?.minIntervalMs ?: 0
        return if (ms > 0) (ms / MS_PER_S).seconds else default
    }

    private fun dozeLike(
        samples: List<HealthSample>,
        start: Instant,
        end: Instant,
    ): Boolean {
        val before = samples.lastOrNull { it.at <= start }
        val after = samples.firstOrNull { it.at >= end }
        return listOf(before, after).all { it != null && !it.isCharging && it.motion == STILL }
    }

    private fun drain(samples: List<HealthSample>): Double? {
        val runs = runsNotCharging(samples.sortedBy { it.at }).filter { it.size >= 2 && it.length() >= MIN_RUN }
        val slopes =
            runs.mapNotNull { run ->
                val start = run.first().at
                slope(run.map { Point((it.at - start) / 1.hours, it.levelPercent.toDouble()) })?.let { it to run.length() }
            }
        if (slopes.isEmpty()) return null
        val weight = slopes.sumOf { (_, length) -> length.inWholeMilliseconds.toDouble() }
        return -slopes.sumOf { (perHour, length) -> perHour * length.inWholeMilliseconds } / weight
    }

    /** Consecutive samples that are not charging; a charging sample ends a run. */
    private fun runsNotCharging(samples: List<HealthSample>): List<List<HealthSample>> {
        val runs = mutableListOf<MutableList<HealthSample>>()
        var open = false
        for (sample in samples) {
            if (sample.isCharging) {
                open = false
            } else {
                if (!open) runs += mutableListOf<HealthSample>()
                open = true
                runs.last() += sample
            }
        }
        return runs
    }

    private fun List<HealthSample>.length(): Duration = last().at - first().at
}
