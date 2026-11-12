package veeci.practicing.rts.tracking.application.port.out

import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.TrackingSession
import kotlin.time.Instant

interface TrackingSessionRepository {
    suspend fun find(id: SessionId): TrackingSession?

    /** Inserts the session, or updates it if it exists. */
    suspend fun save(session: TrackingSession)

    /** The latest last-seen time over each driver's sessions; drivers with no session are missing. */
    suspend fun lastSeenByDriver(drivers: Collection<UserId>): Map<UserId, Instant>
}
