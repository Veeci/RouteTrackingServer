package veeci.practicing.rts.tracking.adapter.out.persistence

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import veeci.practicing.rts.tracking.application.port.out.Purged
import veeci.practicing.rts.tracking.application.port.out.RetentionRepository
import veeci.practicing.rts.tracking.domain.SessionId
import kotlin.time.Instant

/** Runs inside the caller's transaction. Finds sessions through `tracking_sessions_unpurged` (V18). */
class ExposedRetentionRepository : RetentionRepository {
    override suspend fun expiredSessions(
        cutoff: Instant,
        limit: Int,
    ): List<SessionId> =
        TrackingSessionsTable
            .select(TrackingSessionsTable.id)
            .where { TrackingSessionsTable.purgedAt.isNull() and (TrackingSessionsTable.lastSeenAt less cutoff) }
            .orderBy(TrackingSessionsTable.lastSeenAt to SortOrder.ASC, TrackingSessionsTable.id to SortOrder.ASC)
            .limit(limit)
            .map { SessionId(it[TrackingSessionsTable.id]) }

    override suspend fun purge(
        session: SessionId,
        now: Instant,
    ): Purged {
        val fixes = FixesTable.deleteWhere { sessionId eq session.value }
        val samples = DeviceStatusSamplesTable.deleteWhere { sessionId eq session.value }
        TrackingSessionsTable.update({ TrackingSessionsTable.id eq session.value }) { it[purgedAt] = now }
        return Purged(fixes, samples)
    }
}
