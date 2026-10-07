package veeci.practicing.rts.tracking.application.port.out

import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.TrackingSession

interface TrackingSessionRepository {
    suspend fun find(id: SessionId): TrackingSession?

    /** Inserts the session, or updates it if it exists. */
    suspend fun save(session: TrackingSession)
}
