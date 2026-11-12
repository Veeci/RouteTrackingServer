package veeci.practicing.rts.trip.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The thresholds of [ArrivalRules]. The gap between [arrivingWithinM] and [arrivingResetBeyondM], and the fixes
 * in a row that each needs, are the hysteresis: GPS noise around one distance cannot make "arriving" flicker.
 */
data class ArrivalConfig(
    val arrivingWithinM: Double = 200.0,
    val arrivingFixes: Int = 2,
    val arrivingResetBeyondM: Double = 300.0,
    val arrivingResetFixes: Int = 2,
    val arrivedWithinM: Double = 30.0,
    val arrivedFixes: Int = 3,
    val arrivedSlowerThanMps: Double = 1.5,
    val arrivedSlowFor: Duration = 10.seconds,
)

/** One fix of the driver, as the arrival rules see it: the straight-line distance to the pickup. */
data class ArrivalSample(
    val remainingM: Double,
    /** As the device measured it; null when it did not, which leaves only the fixes-in-a-row arrival rule. */
    val speedMps: Double?,
    val at: Instant,
)

/** What the arrival rules remember between two fixes of one trip. Stored, so it survives a restart. */
data class ArrivalState(
    /** Fixes in a row within the arriving distance. */
    val near: Int = 0,
    /** Fixes in a row beyond the reset distance. */
    val far: Int = 0,
    /** DriverArriving was raised and has not been reset yet. */
    val announced: Boolean = false,
    /** Fixes in a row within the arrived distance. */
    val atPickup: Int = 0,
    /** Since when the driver is within the arrived distance and slow; null when they are not. */
    val slowSince: Instant? = null,
    /** The time of the last fix counted. A fix at or before it is a repeat (the outbox delivers at least once). */
    val lastAt: Instant? = null,
)

/** The new state, and the sample that made the driver arriving or arrived, if one did. */
data class ArrivalOutcome(
    val state: ArrivalState,
    val arriving: ArrivalSample? = null,
    val arrived: ArrivalSample? = null,
)

/**
 * Decides, fix by fix, when the driver is arriving at the pickup and when they have arrived. Pure: no clock,
 * no storage. The caller applies it only while the trip is ACCEPTED.
 *
 * | Rule     | When                                                                                  |
 * |----------|---------------------------------------------------------------------------------------|
 * | arriving | 2 fixes in a row within 200 m; again only after 2 fixes in a row beyond 300 m         |
 * | arrived  | 3 fixes in a row within 30 m, or within 30 m and slower than 1.5 m/s for 10 s         |
 */
class ArrivalRules(
    private val config: ArrivalConfig = ArrivalConfig(),
) {
    fun evaluate(
        state: ArrivalState,
        sample: ArrivalSample,
    ): ArrivalOutcome {
        if (state.lastAt != null && sample.at <= state.lastAt) return ArrivalOutcome(state)
        val (approach, arriving) = approach(state, sample)
        val (atPickup, arrived) = atPickup(state, sample)
        return ArrivalOutcome(
            state.copy(
                near = approach.near,
                far = approach.far,
                announced = approach.announced,
                atPickup = atPickup.atPickup,
                slowSince = atPickup.slowSince,
                lastAt = sample.at,
            ),
            arriving,
            arrived,
        )
    }

    /** The arriving rule: the counts in a row, and the sample that raised arriving, if one did. */
    private fun approach(
        state: ArrivalState,
        sample: ArrivalSample,
    ): Pair<ArrivalState, ArrivalSample?> {
        val near = if (sample.remainingM <= config.arrivingWithinM) state.near + 1 else 0
        val far = if (sample.remainingM > config.arrivingResetBeyondM) state.far + 1 else 0
        val stillAnnounced = state.announced && far < config.arrivingResetFixes
        val arriving = sample.takeIf { !stillAnnounced && near >= config.arrivingFixes }
        return state.copy(near = near, far = far, announced = stillAnnounced || arriving != null) to arriving
    }

    /** The arrived rule: fixes in a row at the pickup, or slow at the pickup for long enough. */
    private fun atPickup(
        state: ArrivalState,
        sample: ArrivalSample,
    ): Pair<ArrivalState, ArrivalSample?> {
        val within = sample.remainingM <= config.arrivedWithinM
        val atPickup = if (within) state.atPickup + 1 else 0
        val slow = within && sample.speedMps != null && sample.speedMps < config.arrivedSlowerThanMps
        val slowSince = if (slow) state.slowSince ?: sample.at else null
        val longEnough = slowSince != null && sample.at - slowSince >= config.arrivedSlowFor
        val arrived = sample.takeIf { atPickup >= config.arrivedFixes || longEnough }
        return state.copy(atPickup = atPickup, slowSince = slowSince) to arrived
    }
}
