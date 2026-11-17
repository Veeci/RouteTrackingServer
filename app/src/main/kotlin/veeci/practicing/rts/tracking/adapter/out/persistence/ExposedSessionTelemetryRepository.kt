package veeci.practicing.rts.tracking.adapter.out.persistence

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import veeci.practicing.rts.tracking.application.api.SessionMark
import veeci.practicing.rts.tracking.application.api.TelemetryFix
import veeci.practicing.rts.tracking.application.port.out.SessionTelemetryRepository
import veeci.practicing.rts.tracking.domain.SessionId
import kotlin.time.Instant

/**
 * Runs inside the caller's transaction. Postgres finds idle sessions through `tracking_sessions_last_seen` (V16)
 * and a session's fixes through `fixes_session_time`.
 */
class ExposedSessionTelemetryRepository : SessionTelemetryRepository {
    override suspend fun idleSessions(
        after: SessionMark?,
        lastSeenBefore: Instant,
        limit: Int,
    ): List<SessionMark> =
        TrackingSessionsTable
            .select(TrackingSessionsTable.lastSeenAt, TrackingSessionsTable.id)
            .where { (TrackingSessionsTable.lastSeenAt lessEq lastSeenBefore) and after.startsAfter() }
            .orderBy(TrackingSessionsTable.lastSeenAt to SortOrder.ASC, TrackingSessionsTable.id to SortOrder.ASC)
            .limit(limit)
            .map { SessionMark(it[TrackingSessionsTable.lastSeenAt], it[TrackingSessionsTable.id]) }

    override suspend fun fixes(id: SessionId): List<TelemetryFix> =
        FixesTable
            .select(FixesTable.recordedAt, FixesTable.accuracyM, FixesTable.provider)
            .where { FixesTable.sessionId eq id.value }
            .orderBy(FixesTable.recordedAt to SortOrder.ASC, FixesTable.seq to SortOrder.ASC, FixesTable.idx to SortOrder.ASC)
            .map { TelemetryFix(it[FixesTable.recordedAt], it[FixesTable.accuracyM].toDouble(), it[FixesTable.provider]) }

    override suspend fun rejectionReasons(id: SessionId): List<List<String>> =
        FixBatchesTable
            .select(FixBatchesTable.rejections)
            .where { FixBatchesTable.sessionId eq id.value }
            .map { row -> row[FixBatchesTable.rejections].map { it.reason } }

    /** Keyset paging: sessions after the mark in (last_seen_at, id) order. */
    private fun SessionMark?.startsAfter(): Op<Boolean> {
        if (this == null) return Op.TRUE
        return (TrackingSessionsTable.lastSeenAt greater lastSeenAt) or
            ((TrackingSessionsTable.lastSeenAt eq lastSeenAt) and (TrackingSessionsTable.id greater sessionId))
    }
}
