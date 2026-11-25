package veeci.practicing.rts.tracking.adapter.out.persistence

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import veeci.practicing.rts.tracking.application.api.LatestPosition
import veeci.practicing.rts.tracking.application.api.LatestStatus
import veeci.practicing.rts.tracking.application.api.TelemetryDevice
import veeci.practicing.rts.tracking.application.api.TelemetrySession
import veeci.practicing.rts.tracking.application.port.out.DriverActivityRepository
import java.sql.ResultSet
import java.util.UUID
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Runs inside the caller's transaction. The two "latest per driver" reads use Postgres `DISTINCT ON`, which keeps the
 * first row of each driver in the ORDER BY: one pass over the table, no window function.
 */
class ExposedDriverActivityRepository : DriverActivityRepository {
    override suspend fun recentSessions(
        driver: UUID,
        limit: Int,
    ): List<TelemetrySession> =
        TrackingSessionsTable
            .selectAll()
            .where { TrackingSessionsTable.driverId eq driver }
            .orderBy(TrackingSessionsTable.lastSeenAt to SortOrder.DESC, TrackingSessionsTable.id to SortOrder.DESC)
            .limit(limit)
            .map {
                TelemetrySession(
                    it[TrackingSessionsTable.id],
                    it[TrackingSessionsTable.driverId],
                    it[TrackingSessionsTable.startedAt],
                    it[TrackingSessionsTable.lastSeenAt],
                    TelemetryDevice(
                        it[TrackingSessionsTable.deviceManufacturer],
                        it[TrackingSessionsTable.deviceModel],
                        it[TrackingSessionsTable.deviceAndroidSdk],
                        it[TrackingSessionsTable.deviceRom],
                        it[TrackingSessionsTable.deviceHasGms],
                    ).takeUnless { device -> device == TelemetryDevice(null, null, null, null, null) },
                )
            }

    // Ties on recorded_at go to the later batch and index, the order the fixes were stored in.
    override suspend fun latestPositions(): List<LatestPosition> =
        query(
            """
            SELECT DISTINCT ON (s.driver_id) s.driver_id, f.lat, f.lng, f.speed_mps, f.bearing_deg, f.recorded_at
            FROM fixes f JOIN tracking_sessions s ON s.id = f.session_id
            WHERE s.driver_id IS NOT NULL
            ORDER BY s.driver_id, f.recorded_at DESC, f.seq DESC, f.idx DESC
            """.trimIndent(),
        ) {
            LatestPosition(
                driverId = it.getObject("driver_id", UUID::class.java),
                lat = it.getDouble("lat"),
                lng = it.getDouble("lng"),
                speedMps = it.floatOrNull("speed_mps"),
                bearingDeg = it.floatOrNull("bearing_deg"),
                recordedAt = it.instant("recorded_at"),
            )
        }

    override suspend fun latestStatuses(): List<LatestStatus> =
        query(
            """
            SELECT DISTINCT ON (s.driver_id) s.driver_id, d.at, d.level_percent, d.is_charging, d.motion
            FROM device_status_samples d JOIN tracking_sessions s ON s.id = d.session_id
            WHERE s.driver_id IS NOT NULL
            ORDER BY s.driver_id, d.at DESC
            """.trimIndent(),
        ) {
            LatestStatus(
                driverId = it.getObject("driver_id", UUID::class.java),
                at = it.instant("at"),
                levelPercent = it.getInt("level_percent"),
                isCharging = it.getBoolean("is_charging"),
                motion = it.getString("motion"),
            )
        }

    // tracking_sessions_driver_last_seen (V9) answers it with an index-only scan.
    override suspend fun latestHeard(): Map<UUID, Instant> =
        query(
            """
            SELECT driver_id, max(last_seen_at) AS heard_at
            FROM tracking_sessions
            WHERE driver_id IS NOT NULL
            GROUP BY driver_id
            """.trimIndent(),
        ) { it.getObject("driver_id", UUID::class.java) to it.instant("heard_at") }.toMap()

    private fun <T> query(
        sql: String,
        read: (ResultSet) -> T,
    ): List<T> =
        TransactionManager.current().exec(sql) { rows ->
            buildList { while (rows.next()) add(read(rows)) }
        } ?: emptyList()

    private fun ResultSet.floatOrNull(column: String): Double? = getFloat(column).takeUnless { wasNull() }?.toDouble()

    private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant().toKotlinInstant()
}
