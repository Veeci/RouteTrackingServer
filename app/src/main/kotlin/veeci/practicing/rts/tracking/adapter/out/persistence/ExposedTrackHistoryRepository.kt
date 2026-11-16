package veeci.practicing.rts.tracking.adapter.out.persistence

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.TrackCursor
import veeci.practicing.rts.tracking.application.api.TrackPoint
import veeci.practicing.rts.tracking.application.port.out.TrackHistoryRepository
import kotlin.time.Instant

/**
 * Runs inside the caller's transaction. Postgres finds the driver's sessions through
 * `tracking_sessions_driver_last_seen` and each session's fixes through `fixes_session_time`.
 */
class ExposedTrackHistoryRepository : TrackHistoryRepository {
    override suspend fun fixes(
        driver: UserId,
        from: Instant,
        to: Instant,
        after: TrackCursor?,
        limit: Int,
    ): List<TrackPoint> =
        FixesTable
            .join(TrackingSessionsTable, JoinType.INNER, FixesTable.sessionId, TrackingSessionsTable.id)
            .select(POINT_COLUMNS)
            .where {
                (TrackingSessionsTable.driverId eq driver.value) and
                    (FixesTable.recordedAt greaterEq from) and
                    (FixesTable.recordedAt less to) and
                    after.startsAfter()
            }.orderBy(
                FixesTable.recordedAt to SortOrder.ASC,
                FixesTable.sessionId to SortOrder.ASC,
                FixesTable.seq to SortOrder.ASC,
                FixesTable.idx to SortOrder.ASC,
            ).limit(limit)
            .map { it.toPoint() }

    override suspend fun rejectionReasons(
        driver: UserId,
        from: Instant,
        to: Instant,
    ): List<List<String>> =
        FixBatchesTable
            .join(TrackingSessionsTable, JoinType.INNER, FixBatchesTable.sessionId, TrackingSessionsTable.id)
            .select(FixBatchesTable.rejections)
            .where {
                (TrackingSessionsTable.driverId eq driver.value) and
                    (FixBatchesTable.receivedAt greaterEq from) and
                    (FixBatchesTable.receivedAt less to)
            }.map { row -> row[FixBatchesTable.rejections].map { it.reason } }

    /** Keyset paging: rows after the cursor in (recorded_at, session_id, seq, idx) order. */
    private fun TrackCursor?.startsAfter(): Op<Boolean> {
        if (this == null) return Op.TRUE
        val sameTime = FixesTable.recordedAt eq recordedAt
        val sameSession = sameTime and (FixesTable.sessionId eq sessionId)
        val sameBatch = sameSession and (FixesTable.seq eq seq)
        return (FixesTable.recordedAt greater recordedAt) or
            (sameTime and (FixesTable.sessionId greater sessionId)) or
            (sameSession and (FixesTable.seq greater seq)) or
            (sameBatch and (FixesTable.idx greater idx))
    }

    private fun ResultRow.toPoint() =
        TrackPoint(
            lat = this[FixesTable.lat],
            lng = this[FixesTable.lng],
            recordedAt = this[FixesTable.recordedAt],
            accuracyM = this[FixesTable.accuracyM].toDouble(),
            speedMps = this[FixesTable.speedMps]?.toDouble(),
            derivedSpeedMps = this[FixesTable.derivedSpeedMps]?.toDouble(),
            cursor = TrackCursor(this[FixesTable.recordedAt], this[FixesTable.sessionId], this[FixesTable.seq], this[FixesTable.idx]),
        )

    private companion object {
        val POINT_COLUMNS =
            listOf(
                FixesTable.sessionId,
                FixesTable.seq,
                FixesTable.idx,
                FixesTable.lat,
                FixesTable.lng,
                FixesTable.recordedAt,
                FixesTable.accuracyM,
                FixesTable.speedMps,
                FixesTable.derivedSpeedMps,
            )
    }
}
