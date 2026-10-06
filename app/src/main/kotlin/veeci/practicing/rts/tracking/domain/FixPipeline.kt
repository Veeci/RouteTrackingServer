package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.shared.geo.Meters
import kotlin.time.DurationUnit
import kotlin.time.Instant

/** What the pipeline needs to know besides the batch. */
data class PipelineContext(
    val now: Instant,
    /** The latest accepted fix of the session recorded at or before the batch's earliest fix; null if none. */
    val previous: AcceptedFix?,
)

data class PipelineResult(
    /** In time order. */
    val accepted: List<AcceptedFix>,
    /** In index order. */
    val rejected: List<Rejection>,
)

/**
 * Decides which fixes of a batch to keep. Each fix is validated, then checked by the rules in order (the first
 * rule that objects decides the reason), then enriched with distances. Fixes are processed in time order, so the
 * jump and duplicate rules always compare a fix with the accepted fix just before it.
 */
class FixPipeline(
    config: PipelineConfig,
) {
    private val rules: List<FixRule> =
        listOf(MockRule(config.rejectMock), AccuracyRule(config), TimeWindowRule(config), DuplicateRule, JumpRule(config))

    fun process(
        batch: List<FixReading>,
        context: PipelineContext,
    ): PipelineResult {
        val accepted = mutableListOf<AcceptedFix>()
        val rejected = mutableListOf<Rejection>()
        var previous = context.previous
        // sortedBy is stable: fixes with the same time keep their order, so the first one sent is kept.
        for ((index, reading) in batch.withIndex().sortedBy { it.value.recordedAt }) {
            val fix = reading.validate()
            if (fix == null) {
                rejected += Rejection(index, RejectionReason.INVALID_VALUE)
                continue
            }
            val ruleContext = RuleContext(context.now, previous)
            val reason = rules.firstNotNullOfOrNull { it.check(fix, ruleContext) }
            if (reason != null) {
                rejected += Rejection(index, reason)
            } else {
                previous = enrich(fix, index, previous).also { accepted += it }
            }
        }
        return PipelineResult(accepted, rejected.sortedBy { it.index })
    }

    private fun enrich(
        fix: LocationFix,
        index: Int,
        previous: AcceptedFix?,
    ): AcceptedFix {
        if (previous == null) {
            return AcceptedFix(fix, index, distanceFromPrev = Meters(0.0), cumulative = Meters(0.0), derivedSpeedMps = null)
        }
        val distance = Haversine.distance(previous.fix.point, fix.point)
        val seconds = (fix.recordedAt - previous.fix.recordedAt).toDouble(DurationUnit.SECONDS)
        return AcceptedFix(fix, index, distance, previous.cumulative + distance, derivedSpeedMps = distance.value / seconds)
    }
}
