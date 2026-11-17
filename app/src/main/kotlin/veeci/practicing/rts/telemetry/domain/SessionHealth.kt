package veeci.practicing.rts.telemetry.domain

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Instant

/** One accepted fix, as health needs it. */
data class HealthFix(
    val at: Instant,
    val accuracyM: Double,
    val provider: String,
)

/** One device_status sample, as health needs it. */
data class HealthSample(
    val at: Instant,
    val levelPercent: Int,
    val isCharging: Boolean,
    /** The Activity Recognition label, such as STILL or IN_VEHICLE; null when the device sent none. */
    val motion: String?,
)

/** A SET_CONSTRAINT the device applied at [appliedAt]. It holds until the next one. */
data class ConstraintChange(
    val appliedAt: Instant,
    val minIntervalMs: Long,
)

/** Everything one session's health is computed from. */
data class HealthInput(
    val fixes: List<HealthFix>,
    /** Rejected fixes by reason. */
    val rejections: Map<String, Int>,
    val samples: List<HealthSample>,
    /** Every constraint the driver applied up to the session's end, including the one in effect at its start. */
    val constraints: List<ConstraintChange>,
    /** The fix interval when no constraint says otherwise. */
    val defaultInterval: Duration,
)

/** A pause between two accepted fixes that is longer than the device should allow. */
data class Gap(
    val start: Instant,
    val durationS: Double,
    /** The interval the device should have kept when the gap started. */
    val expectedIntervalS: Double,
    /** The phone was still and not charging on both sides, and the gap lasted 5 minutes or more. */
    val likelyDoze: Boolean,
)

/** How one tracking session behaved (phase 9). Percentiles are null when there is nothing to measure. */
data class SessionHealth(
    val fixCount: Int,
    val rejectedCount: Int,
    /** Accepted over accepted plus rejected; null when the session sent no fix at all. */
    val acceptedRatio: Double?,
    val rejectionsByReason: Map<String, Int>,
    val intervalP50S: Double?,
    val intervalP95S: Double?,
    val accuracyP50M: Double?,
    val accuracyP95M: Double?,
    val gaps: List<Gap>,
    /** Battery percent lost per hour while not charging; positive means the battery runs down. */
    val batteryDrainPctPerHour: Double?,
    /** Each provider's share of the accepted fixes. */
    val providerMix: Map<String, Double>,
    /** The provider with the largest share; a tie goes to the first name in alphabetical order. */
    val primaryProvider: String?,
)

/** The phone of a session, the key the provider report groups by. */
data class DeviceKey(
    val manufacturer: String?,
    val model: String?,
    val androidSdk: Int?,
    val rom: String?,
    val hasGms: Boolean?,
)

/** A computed health and the session facts it belongs to, as stored in `session_health`. */
data class SessionHealthRecord(
    val sessionId: UUID,
    val driverId: UUID?,
    val startedAt: Instant,
    /** The session's last-seen time when health was computed. A later one means the session resumed. */
    val sessionLastSeenAt: Instant,
    val device: DeviceKey?,
    val computedAt: Instant,
    val health: SessionHealth,
)
