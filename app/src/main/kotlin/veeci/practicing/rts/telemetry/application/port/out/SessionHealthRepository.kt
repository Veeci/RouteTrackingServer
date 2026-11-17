package veeci.practicing.rts.telemetry.application.port.out

import veeci.practicing.rts.telemetry.domain.SessionHealthRecord
import veeci.practicing.rts.tracking.application.api.SessionMark
import java.util.UUID
import kotlin.time.Instant

/** Joins the caller's transaction. */
interface SessionHealthRepository {
    /** Inserts the record, or replaces the session's earlier one. */
    suspend fun save(record: SessionHealthRecord)

    suspend fun find(sessionId: UUID): SessionHealthRecord?

    /** The record of the driver's most recently started session that has one. */
    suspend fun latestForDriver(driverId: UUID): SessionHealthRecord?

    /** Every record of a session that started in [from, to). */
    suspend fun startedBetween(
        from: Instant,
        to: Instant,
    ): List<SessionHealthRecord>

    /** Where the health job stopped; null before its first run. */
    suspend fun watermark(): SessionMark?

    suspend fun moveWatermark(to: SessionMark)
}
