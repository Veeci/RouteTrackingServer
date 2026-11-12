package veeci.practicing.rts.tracking.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.DriverPresence
import veeci.practicing.rts.tracking.application.port.out.TrackingSessionRepository
import kotlin.time.Instant

class TrackingPresence(
    private val sessions: TrackingSessionRepository,
    private val transactions: TransactionRunner,
) : DriverPresence {
    override suspend fun lastSeen(drivers: Collection<UserId>): Map<UserId, Instant> =
        if (drivers.isEmpty()) emptyMap() else transactions.inTransaction { sessions.lastSeenByDriver(drivers) }
}
