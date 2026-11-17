package veeci.practicing.rts.simulator.fleet

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** Percentiles by nearest rank over a sample of durations. */
data class Spread(
    val count: Int,
    val p50: Duration,
    val p95: Duration,
    val max: Duration,
) {
    override fun toString(): String = if (count == 0) "no samples" else "p50 ${p50.ms()}, p95 ${p95.ms()}, max ${max.ms()} over $count"

    private fun Duration.ms() = toString(DurationUnit.MILLISECONDS, 1)

    companion object {
        private const val P50 = 0.50
        private const val P95 = 0.95

        fun of(samples: Collection<Duration>): Spread {
            val sorted = samples.sorted()
            if (sorted.isEmpty()) return Spread(0, Duration.ZERO, Duration.ZERO, Duration.ZERO)
            return Spread(sorted.size, sorted.rank(P50), sorted.rank(P95), sorted.last())
        }

        private fun List<Duration>.rank(p: Double): Duration = this[(kotlin.math.ceil(p * size).toInt() - 1).coerceIn(indices)]
    }
}

/** What a fleet run counts, from many coroutines at once. */
class FleetStats {
    val connectedDrivers = AtomicInteger()
    val batches = AtomicInteger()
    val accepted = AtomicInteger()
    val connectionFailures = AtomicInteger()
    val rejected = ConcurrentHashMap<String, AtomicInteger>()
    val ackLatencies = ConcurrentLinkedQueue<Duration>()
    val deltaGaps = ConcurrentLinkedQueue<Duration>()
    val deltas = AtomicInteger()
    val clusters = AtomicInteger()
    val connectedDashboards = AtomicInteger()

    fun rejected(reason: String) {
        rejected.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
    }
}

/** The result of a fleet run, and whether the delta cadence was within [expectedGap] ± [tolerance] at p95. */
data class FleetReport(
    val drivers: Int,
    val dashboards: Int,
    val duration: Duration,
    val stats: FleetStats,
    val expectedGap: Duration,
    val tolerance: Duration,
) {
    val gaps: Spread = Spread.of(stats.deltaGaps)
    val ackLatency: Spread = Spread.of(stats.ackLatencies)

    /** True when no dashboard ran, or when the p95 gap between two deltas is within the expected tick ± tolerance. */
    val cadenceOk: Boolean
        get() = dashboards == 0 || (gaps.count > 0 && gaps.p95 >= expectedGap - tolerance && gaps.p95 <= expectedGap + tolerance)

    fun lines(): List<String> =
        listOf(
            "Fleet: $drivers drivers (${stats.connectedDrivers.get()} connected at least once), $dashboards dashboards, for $duration",
            "Fixes: ${stats.batches.get()} batches acked, ${stats.accepted.get()} fixes accepted, rejected ${stats.rejected.mapValues {
                it.value.get()
            }}, ${stats.connectionFailures.get()} failed connections",
            "Ack latency: $ackLatency",
            "Dashboards: ${stats.connectedDashboards.get()} connected, ${stats.deltas.get()} deltas, ${stats.clusters.get()} clusters",
            "Gap between deltas: $gaps; expected $expectedGap ± $tolerance at p95: ${if (cadenceOk) "OK" else "OUT OF RANGE"}",
        )
}
