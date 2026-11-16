package veeci.practicing.rts.tracking.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.DriverTrack
import veeci.practicing.rts.tracking.application.api.TrackCursor
import veeci.practicing.rts.tracking.application.api.TrackPoint
import veeci.practicing.rts.tracking.application.port.out.TrackHistoryRepository
import kotlin.time.Instant

class TrackingHistory(
    private val history: TrackHistoryRepository,
    private val transactions: TransactionRunner,
) : DriverTrack {
    override suspend fun fixes(
        driver: UserId,
        from: Instant,
        to: Instant,
        after: TrackCursor?,
        limit: Int,
    ): List<TrackPoint> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return if (from >= to) emptyList() else transactions.inTransaction { history.fixes(driver, from, to, after, limit) }
    }

    override suspend fun rejections(
        driver: UserId,
        from: Instant,
        to: Instant,
    ): Map<String, Int> =
        if (from >= to) {
            emptyMap()
        } else {
            transactions
                .inTransaction { history.rejectionReasons(driver, from, to) }
                .flatten()
                .groupingBy { it }
                .eachCount()
        }
}
