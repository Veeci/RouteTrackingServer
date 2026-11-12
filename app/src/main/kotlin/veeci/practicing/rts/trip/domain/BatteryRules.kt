package veeci.practicing.rts.trip.domain

import kotlin.time.Instant

/** The thresholds of [BatteryRules]. The gap between the two levels keeps the notice from flickering. */
data class BatteryConfig(
    val lowAtOrBelowPercent: Int = 15,
    val resetAtOrAbovePercent: Int = 25,
    /** The SDK profile that counts as low whatever the level (for example a hot battery). */
    val criticalProfile: String = "CRITICAL",
)

/** One device status of the driver, as the battery rule sees it. */
data class BatterySample(
    val levelPercent: Int,
    val isCharging: Boolean,
    val profile: String?,
    val at: Instant,
)

/** What the battery rule remembers between two statuses of one trip. Stored, so it survives a restart. */
data class BatteryState(
    /** DriverLowBattery was raised and has not been reset yet. */
    val announced: Boolean = false,
    /** The time of the last status counted. A status at or before it is a repeat. */
    val lastAt: Instant? = null,
)

/** The new state, and the sample that raised the low battery notice, if one did. */
data class BatteryOutcome(
    val state: BatteryState,
    val low: BatterySample? = null,
)

/**
 * Decides when the driver's battery is low. Pure.
 *
 * | Low when                                            | Reset when                     |
 * |-----------------------------------------------------|--------------------------------|
 * | 15 % or less and not charging, or profile CRITICAL  | 25 % or more, or charging      |
 *
 * Low wins over reset: a CRITICAL profile while charging (a hot battery) is still low.
 */
class BatteryRules(
    private val config: BatteryConfig = BatteryConfig(),
) {
    fun evaluate(
        state: BatteryState,
        sample: BatterySample,
    ): BatteryOutcome {
        if (state.lastAt != null && sample.at <= state.lastAt) return BatteryOutcome(state)
        val low =
            (sample.levelPercent <= config.lowAtOrBelowPercent && !sample.isCharging) ||
                sample.profile == config.criticalProfile
        val reset = sample.levelPercent >= config.resetAtOrAbovePercent || sample.isCharging
        return when {
            low && !state.announced -> BatteryOutcome(BatteryState(announced = true, lastAt = sample.at), sample)
            low -> BatteryOutcome(state.copy(lastAt = sample.at))
            reset -> BatteryOutcome(BatteryState(announced = false, lastAt = sample.at))
            else -> BatteryOutcome(state.copy(lastAt = sample.at))
        }
    }
}

/** Every rule the trip context applies to the driver's stream, with their thresholds. */
data class TripRules(
    val arrival: ArrivalRules = ArrivalRules(),
    val battery: BatteryRules = BatteryRules(),
)
