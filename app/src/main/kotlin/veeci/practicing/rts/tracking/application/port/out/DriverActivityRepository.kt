package veeci.practicing.rts.tracking.application.port.out

import veeci.practicing.rts.tracking.application.api.LatestPosition
import veeci.practicing.rts.tracking.application.api.LatestStatus
import veeci.practicing.rts.tracking.application.api.TelemetrySession
import java.util.UUID
import kotlin.time.Instant

/** Reads for the fleet view (phase 11). Runs inside the caller's transaction. */
interface DriverActivityRepository {
    suspend fun recentSessions(
        driver: UUID,
        limit: Int,
    ): List<TelemetrySession>

    suspend fun latestPositions(): List<LatestPosition>

    suspend fun latestStatuses(): List<LatestStatus>

    suspend fun latestHeard(): Map<UUID, Instant>
}
