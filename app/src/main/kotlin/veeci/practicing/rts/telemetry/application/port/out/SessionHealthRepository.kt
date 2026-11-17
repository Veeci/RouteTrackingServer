package veeci.practicing.rts.telemetry.application.port.out

import veeci.practicing.rts.telemetry.domain.SessionHealthRecord
import veeci.practicing.rts.tracking.application.api.SessionMark
import java.util.UUID

/** Joins the caller's transaction. */
interface SessionHealthRepository {
    /** Inserts the record, or replaces the session's earlier one. */
    suspend fun save(record: SessionHealthRecord)

    suspend fun find(sessionId: UUID): SessionHealthRecord?

    /** Where the health job stopped; null before its first run. */
    suspend fun watermark(): SessionMark?

    suspend fun moveWatermark(to: SessionMark)
}
