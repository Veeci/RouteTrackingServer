package veeci.practicing.rts.tracking.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.tracking.application.api.DriverActivity
import veeci.practicing.rts.tracking.application.api.LatestPosition
import veeci.practicing.rts.tracking.application.api.LatestStatus
import veeci.practicing.rts.tracking.application.api.TelemetrySession
import veeci.practicing.rts.tracking.application.port.out.DriverActivityRepository
import java.util.UUID

class TrackingActivity(
    private val activity: DriverActivityRepository,
    private val transactions: TransactionRunner,
) : DriverActivity {
    override suspend fun recentSessions(
        driver: UUID,
        limit: Int,
    ): List<TelemetrySession> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return transactions.inTransaction { activity.recentSessions(driver, limit) }
    }

    override suspend fun latestPositions(): List<LatestPosition> = transactions.inTransaction { activity.latestPositions() }

    override suspend fun latestStatuses(): List<LatestStatus> = transactions.inTransaction { activity.latestStatuses() }
}
