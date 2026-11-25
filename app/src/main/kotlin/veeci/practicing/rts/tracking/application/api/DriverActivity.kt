package veeci.practicing.rts.tracking.application.api

import java.util.UUID
import kotlin.time.Instant

/**
 * What drivers did lately, the tracking context's read API for the fleet view (phase 11). The fleet projection is
 * fed by events; [latestPositions] and [latestStatuses] let it be rebuilt from the stored data instead. Every method
 * joins the caller's transaction when there is one.
 */
interface DriverActivity {
    /** The driver's sessions, the most recently seen first, at most [limit]. */
    suspend fun recentSessions(
        driver: UUID,
        limit: Int,
    ): List<TelemetrySession>

    /** The newest accepted fix of every driver that has one. Reads every stored fix: for a rare rebuild only. */
    suspend fun latestPositions(): List<LatestPosition>

    /** The newest device_status sample of every driver that has one. */
    suspend fun latestStatuses(): List<LatestStatus>

    /**
     * When the server last heard from every driver that has a session: the latest last-seen time over their sessions,
     * by the server's clock, as [DriverPresence] answers it. The times [DriverHeard] was told, so a rebuild matches.
     */
    suspend fun latestHeard(): Map<UUID, Instant>
}

/** A driver's newest accepted fix. Speed and bearing as stored (4-byte floats), so a rebuild matches the events. */
data class LatestPosition(
    val driverId: UUID,
    val lat: Double,
    val lng: Double,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val recordedAt: Instant,
)

/** A driver's newest device_status sample. */
data class LatestStatus(
    val driverId: UUID,
    val at: Instant,
    val levelPercent: Int,
    val isCharging: Boolean,
    val motion: String?,
)
