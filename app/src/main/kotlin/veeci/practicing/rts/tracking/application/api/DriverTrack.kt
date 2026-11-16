package veeci.practicing.rts.tracking.application.api

import veeci.practicing.rts.shared.UserId
import java.util.UUID
import kotlin.time.Instant

/**
 * Where a driver drove: the tracking context's read API for trip history (phase 8). A trip has no session id,
 * and a driver may open several sessions during one trip (a reconnect with a new session, or a second phone),
 * so the track is asked for by driver and time window. Every method joins the caller's transaction when there
 * is one.
 */
interface DriverTrack {
    /**
     * Accepted fixes of every session of [driver] with a recorded time in [from, to), oldest first, at most
     * [limit], starting after [after]. Equal times are ordered by session, batch and index, so paging never skips
     * one of two fixes that share a time.
     */
    suspend fun fixes(
        driver: UserId,
        from: Instant,
        to: Instant,
        after: TrackCursor?,
        limit: Int,
    ): List<TrackPoint>

    /**
     * Rejected fixes of [driver]'s sessions, counted by reason, for batches received in [from, to). A rejected
     * fix has no stored time, so the batch's arrival time decides which window it counts in.
     */
    suspend fun rejections(
        driver: UserId,
        from: Instant,
        to: Instant,
    ): Map<String, Int>
}

/** One accepted fix, as other contexts see it. */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val recordedAt: Instant,
    val accuracyM: Double,
    /** What the phone reported, when it did. */
    val speedMps: Double?,
    /** Distance over time from the session's previous accepted fix; null for a session's first fix. */
    val derivedSpeedMps: Double?,
    /** Where this point is in the track: the next page starts after it. */
    val cursor: TrackCursor,
)

/** A position in a driver's track, ordered by (recordedAt, sessionId, seq, idx). */
data class TrackCursor(
    val recordedAt: Instant,
    val sessionId: UUID,
    val seq: Long,
    val idx: Int,
)
