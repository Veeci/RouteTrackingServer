package veeci.practicing.rts.tracking.adapter.out.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.port.out.TrackingSessionRepository
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.TrackingSession
import kotlin.time.Instant

/** Runs inside the caller's transaction (see TransactionRunner); it opens none of its own. */
class ExposedTrackingSessionRepository : TrackingSessionRepository {
    override suspend fun find(id: SessionId): TrackingSession? =
        TrackingSessionsTable
            .selectAll()
            .where { TrackingSessionsTable.id eq id.value }
            .singleOrNull()
            ?.toSession()

    override suspend fun save(session: TrackingSession) {
        // Insert, or update on a reconnect. The device, the driver and the start time of a session never change.
        TrackingSessionsTable.upsert(
            onUpdateExclude = listOf(TrackingSessionsTable.deviceId, TrackingSessionsTable.driverId, TrackingSessionsTable.startedAt),
        ) {
            it[id] = session.id.value
            it[deviceId] = session.deviceId.value
            it[sdkVersion] = session.sdkVersion
            it[startedAt] = session.startedAt
            it[lastSeenAt] = session.lastSeenAt
            it[driverId] = session.driverId?.value
        }
    }

    private fun ResultRow.toSession() =
        TrackingSession(
            id = SessionId(this[TrackingSessionsTable.id]),
            deviceId = DeviceId(this[TrackingSessionsTable.deviceId]),
            sdkVersion = this[TrackingSessionsTable.sdkVersion],
            startedAt = this[TrackingSessionsTable.startedAt],
            lastSeenAt = this[TrackingSessionsTable.lastSeenAt],
            driverId = this[TrackingSessionsTable.driverId]?.let(::UserId),
        )

    override suspend fun lastSeenByDriver(drivers: Collection<UserId>): Map<UserId, Instant> {
        val latest = TrackingSessionsTable.lastSeenAt.max()
        return TrackingSessionsTable
            .select(TrackingSessionsTable.driverId, latest)
            .where { TrackingSessionsTable.driverId inList drivers.map { it.value } }
            .groupBy(TrackingSessionsTable.driverId)
            .associate { UserId(checkNotNull(it[TrackingSessionsTable.driverId])) to checkNotNull(it[latest]) }
    }
}
