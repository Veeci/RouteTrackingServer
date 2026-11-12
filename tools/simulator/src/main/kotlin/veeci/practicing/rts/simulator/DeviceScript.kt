package veeci.practicing.rts.simulator

import veeci.practicing.rts.protocol.BatteryDto
import veeci.practicing.rts.protocol.DeviceStatus
import veeci.practicing.rts.protocol.FixBatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

/**
 * What the simulated phone does besides sending fixes, measured in route time (the recorded times of the fixes):
 * - a `device_status` every [statusEvery], like the SDK's heartbeat, with a battery that starts at [batteryPercent]
 *   and loses [drainPerMinute] percent per minute;
 * - one silence of [pauseFor] (real time) when the route reaches [pauseAt], to make the server report the driver gone.
 */
class DeviceScript(
    private val routeStart: Instant,
    private val batteryPercent: Int = DEFAULT_BATTERY,
    private val drainPerMinute: Double = 0.0,
    private val pauseAt: Duration? = null,
    private val pauseFor: Duration = Duration.ZERO,
    private val statusEvery: Duration = 30.seconds,
) : BatchHooks {
    private var nextStatusAt = routeStart
    private var paused = false

    /** The status to send before [batch], when a heartbeat is due. */
    override fun statusBefore(batch: FixBatch): DeviceStatus? {
        val at = batch.fixes.first().recordedAt
        if (at < nextStatusAt) return null
        while (nextStatusAt <= at) nextStatusAt += statusEvery
        val minutes = (at - routeStart).toDouble(DurationUnit.MINUTES)
        val level = (batteryPercent - drainPerMinute * minutes).toInt().coerceIn(0, MAX_PERCENT)
        return DeviceStatus(at, BatteryDto(level, isCharging = false))
    }

    /** How long to stay silent before [batch]: [pauseFor] once, at the first batch at or after [pauseAt]. */
    override fun pauseBefore(batch: FixBatch): Duration {
        val due = !paused && pauseAt != null && batch.fixes.first().recordedAt - routeStart >= pauseAt
        if (due) paused = true
        return if (due) pauseFor else Duration.ZERO
    }

    companion object {
        const val DEFAULT_BATTERY = 80
        private const val MAX_PERCENT = 100

        /** Parses `--pause-at <route seconds>:<real seconds>`, for example `120:70`. */
        fun parsePause(value: String): Pair<Duration, Duration> {
            val (at, length) = value.split(":").map { it.trim().toLong() }
            return at.seconds to length.seconds
        }
    }
}
