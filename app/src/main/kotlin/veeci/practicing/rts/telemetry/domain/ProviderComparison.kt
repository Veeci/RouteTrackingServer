package veeci.practicing.rts.telemetry.domain

import veeci.practicing.rts.shared.stats.percentile
import kotlin.time.Duration.Companion.hours

/** What the provider report groups sessions by. A null is its own value: "not reported". */
data class ProviderGroupKey(
    val primaryProvider: String?,
    val hasGms: Boolean?,
    val rom: String?,
    val androidSdk: Int?,
)

/**
 * One group of the provider report (phase 9). Each metric is the median over the group's sessions that have a
 * value for it, so one odd session does not move it far. [lowSample]: under 3 sessions, too few to trust.
 */
data class ProviderGroup(
    val key: ProviderGroupKey,
    val sessions: Int,
    val lowSample: Boolean,
    val trackedHours: Double,
    val acceptedRatio: Double?,
    val intervalP50S: Double?,
    val intervalP95S: Double?,
    val accuracyP50M: Double?,
    val accuracyP95M: Double?,
    val batteryDrainPctPerHour: Double?,
    /** Gaps per tracked hour, so that long and short sessions compare fairly. */
    val gapsPerHour: Double?,
    val dozeGapsPerHour: Double?,
)

/** Groups computed session health into the provider report. Pure. */
object ProviderComparison {
    const val MIN_SAMPLE = 3
    private const val MEDIAN = 50.0

    fun of(records: List<SessionHealthRecord>): List<ProviderGroup> =
        records
            .groupBy { it.key() }
            .map { (key, group) -> group(key, group) }
            .sortedWith(
                compareBy<ProviderGroup, String?>(nullsLast()) { it.key.primaryProvider }
                    .thenBy(nullsLast()) { it.key.hasGms }
                    .thenBy(nullsLast()) { it.key.rom }
                    .thenBy(nullsLast()) { it.key.androidSdk },
            )

    private fun group(
        key: ProviderGroupKey,
        records: List<SessionHealthRecord>,
    ): ProviderGroup {
        fun median(value: (SessionHealthRecord) -> Double?) = percentile(records.mapNotNull(value), MEDIAN)
        return ProviderGroup(
            key = key,
            sessions = records.size,
            lowSample = records.size < MIN_SAMPLE,
            trackedHours = records.sumOf { it.hours() },
            acceptedRatio = median { it.health.acceptedRatio },
            intervalP50S = median { it.health.intervalP50S },
            intervalP95S = median { it.health.intervalP95S },
            accuracyP50M = median { it.health.accuracyP50M },
            accuracyP95M = median { it.health.accuracyP95M },
            batteryDrainPctPerHour = median { it.health.batteryDrainPctPerHour },
            gapsPerHour = median { it.perHour(it.health.gaps.size) },
            dozeGapsPerHour = median { record -> record.perHour(record.health.gaps.count { it.likelyDoze }) },
        )
    }

    private fun SessionHealthRecord.key() = ProviderGroupKey(health.primaryProvider, device?.hasGms, device?.rom, device?.androidSdk)

    private fun SessionHealthRecord.hours(): Double = (sessionLastSeenAt - startedAt) / 1.hours

    /** Null for a session with no tracked time: a rate over zero hours means nothing. */
    private fun SessionHealthRecord.perHour(count: Int): Double? = hours().takeIf { it > 0 }?.let { count / it }
}
