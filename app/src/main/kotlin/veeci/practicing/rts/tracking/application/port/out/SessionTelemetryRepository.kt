package veeci.practicing.rts.tracking.application.port.out

import veeci.practicing.rts.tracking.application.api.SessionMark
import veeci.practicing.rts.tracking.application.api.TelemetryFix
import veeci.practicing.rts.tracking.domain.SessionId
import kotlin.time.Instant

/** Reads for session health (phase 9). Runs inside the caller's transaction. */
interface SessionTelemetryRepository {
    suspend fun idleSessions(
        after: SessionMark?,
        lastSeenBefore: Instant,
        limit: Int,
    ): List<SessionMark>

    suspend fun fixes(id: SessionId): List<TelemetryFix>

    /** The rejection reasons of each of the session's batches, one list per batch. */
    suspend fun rejectionReasons(id: SessionId): List<List<String>>
}
