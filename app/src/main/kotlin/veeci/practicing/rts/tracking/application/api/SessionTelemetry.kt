package veeci.practicing.rts.tracking.application.api

import java.util.UUID
import kotlin.time.Instant

/**
 * One tracking session's raw telemetry, the tracking context's read API for session health (phase 9). Health is
 * per session, not per driver and time window like [DriverTrack], and it needs each fix's provider. Every method
 * joins the caller's transaction when there is one.
 */
interface SessionTelemetry {
    suspend fun session(id: UUID): TelemetrySession?

    /**
     * Sessions last seen after [after] and at or before [lastSeenBefore], in (lastSeenAt, sessionId) order, at most
     * [limit]. A session that resumes moves its mark forward, so it comes back in a later call.
     */
    suspend fun idleSessions(
        after: SessionMark?,
        lastSeenBefore: Instant,
        limit: Int,
    ): List<SessionMark>

    /**
     * Every accepted fix of the session, oldest first. Only three values per fix, so an 8-hour session at one fix a
     * second (28 800 fixes) is about 1 MB.
     */
    suspend fun fixes(id: UUID): List<TelemetryFix>

    /** Rejected fixes of the session, by reason. */
    suspend fun rejections(id: UUID): Map<String, Int>

    /** The session's device_status samples, oldest first. */
    suspend fun statusSamples(id: UUID): List<TelemetrySample>
}

/** A session's position in (lastSeenAt, sessionId) order. */
data class SessionMark(
    val lastSeenAt: Instant,
    val sessionId: UUID,
)

data class TelemetrySession(
    val id: UUID,
    /** Null for sessions stored before drivers had accounts. */
    val driverId: UUID?,
    val startedAt: Instant,
    val lastSeenAt: Instant,
    /** Null when no hello of the session carried a device block. */
    val device: TelemetryDevice?,
)

data class TelemetryDevice(
    val manufacturer: String?,
    val model: String?,
    val androidSdk: Int?,
    val rom: String?,
    val hasGms: Boolean?,
)

data class TelemetryFix(
    val recordedAt: Instant,
    val accuracyM: Double,
    /** The provider name, such as GMS_FUSED or AOSP_GPS. */
    val provider: String,
)

data class TelemetrySample(
    val at: Instant,
    val levelPercent: Int,
    val isCharging: Boolean,
    val motion: String?,
)
