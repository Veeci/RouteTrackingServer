package veeci.practicing.rts.tracking.adapter.out.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import veeci.practicing.rts.tracking.application.port.out.TrackingSessionRepository
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.TrackingSession

/** Runs inside the caller's transaction (see TransactionRunner); it opens none of its own. */
class ExposedTrackingSessionRepository : TrackingSessionRepository {
    override suspend fun find(id: SessionId): TrackingSession? =
        TrackingSessionsTable
            .selectAll()
            .where { TrackingSessionsTable.id eq id.value }
            .singleOrNull()
            ?.toSession()

    override suspend fun save(session: TrackingSession) {
        // Insert, or update on a reconnect. The device and the start time of a session never change.
        TrackingSessionsTable.upsert(
            onUpdateExclude = listOf(TrackingSessionsTable.deviceId, TrackingSessionsTable.startedAt),
        ) {
            it[id] = session.id.value
            it[deviceId] = session.deviceId.value
            it[sdkVersion] = session.sdkVersion
            it[startedAt] = session.startedAt
            it[lastSeenAt] = session.lastSeenAt
        }
    }

    private fun ResultRow.toSession() =
        TrackingSession(
            id = SessionId(this[TrackingSessionsTable.id]),
            deviceId = DeviceId(this[TrackingSessionsTable.deviceId]),
            sdkVersion = this[TrackingSessionsTable.sdkVersion],
            startedAt = this[TrackingSessionsTable.startedAt],
            lastSeenAt = this[TrackingSessionsTable.lastSeenAt],
        )
}
