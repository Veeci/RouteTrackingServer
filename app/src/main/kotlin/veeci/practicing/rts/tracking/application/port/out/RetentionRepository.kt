package veeci.practicing.rts.tracking.application.port.out

import veeci.practicing.rts.tracking.domain.SessionId
import kotlin.time.Instant

/** Deletes raw location data (phase 10). Runs inside the caller's transaction. */
interface RetentionRepository {
    /** Sessions not purged yet and last seen before [cutoff], oldest first, at most [limit]. */
    suspend fun expiredSessions(
        cutoff: Instant,
        limit: Int,
    ): List<SessionId>

    /** Deletes the session's fixes and status samples and marks it purged at [now]. */
    suspend fun purge(
        session: SessionId,
        now: Instant,
    ): Purged
}

/** How many rows one purge deleted, per table. */
data class Purged(
    val fixes: Int,
    val statusSamples: Int,
) {
    operator fun plus(other: Purged) = Purged(fixes + other.fixes, statusSamples + other.statusSamples)

    companion object {
        val NONE = Purged(0, 0)
    }
}
