package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.geo.Haversine
import kotlin.time.DurationUnit
import kotlin.time.Instant

internal class RuleContext(
    val now: Instant,
    val previous: AcceptedFix?,
)

internal fun interface FixRule {
    fun check(
        fix: LocationFix,
        context: RuleContext,
    ): RejectionReason?
}

internal class MockRule(
    private val rejectMock: Boolean,
) : FixRule {
    override fun check(
        fix: LocationFix,
        context: RuleContext,
    ): RejectionReason? = if (rejectMock && fix.mock) RejectionReason.MOCK_LOCATION else null
}

internal class AccuracyRule(
    private val config: PipelineConfig,
) : FixRule {
    override fun check(
        fix: LocationFix,
        context: RuleContext,
    ): RejectionReason? = if (fix.accuracy > config.maxAccuracy) RejectionReason.POOR_ACCURACY else null
}

internal class TimeWindowRule(
    private val config: PipelineConfig,
) : FixRule {
    override fun check(
        fix: LocationFix,
        context: RuleContext,
    ): RejectionReason? =
        when {
            fix.recordedAt > context.now + config.maxClockSkew -> RejectionReason.FUTURE_TIMESTAMP
            fix.recordedAt < context.now - config.maxAge -> RejectionReason.TOO_OLD
            else -> null
        }
}

internal object DuplicateRule : FixRule {
    override fun check(
        fix: LocationFix,
        context: RuleContext,
    ): RejectionReason? = if (fix.recordedAt == context.previous?.fix?.recordedAt) RejectionReason.DUPLICATE_TIMESTAMP else null
}

internal class JumpRule(
    private val config: PipelineConfig,
) : FixRule {
    private companion object {
        const val MIN_SATELLITES = 4
        const val WEAK_FIX_TOLERANCE = 0.5
    }

    override fun check(
        fix: LocationFix,
        context: RuleContext,
    ): RejectionReason? {
        val previous = context.previous?.fix ?: return null
        val seconds = (fix.recordedAt - previous.recordedAt).toDouble(DurationUnit.SECONDS)
        val tolerance = (previous.accuracy + fix.accuracy).value * toleranceFactor(fix)
        val moved = (Haversine.distance(previous.point, fix.point).value - tolerance).coerceAtLeast(0.0)
        return if (seconds > 0 && moved / seconds > config.maxSpeedMps) RejectionReason.IMPLAUSIBLE_JUMP else null
    }

    /** With fewer than 4 satellites a GNSS position is barely determined, so its accuracy radius is trusted less. */
    private fun toleranceFactor(fix: LocationFix): Double {
        val satellites = fix.satellites ?: return 1.0
        return if (satellites.usedInFix < MIN_SATELLITES) WEAK_FIX_TOLERANCE else 1.0
    }
}
