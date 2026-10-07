package veeci.practicing.rts.tracking.application

import veeci.practicing.rts.testing.tracking.InMemoryFixBatchRepository
import veeci.practicing.rts.testing.tracking.InMemoryTrackingSessionRepository
import veeci.practicing.rts.testing.tracking.TrackingPersistenceContract

/** The in-memory repositories that the service tests use pass the same contract as the Postgres ones. */
class InMemoryTrackingPersistenceTest : TrackingPersistenceContract() {
    override val sessions = InMemoryTrackingSessionRepository()
    override val batches = InMemoryFixBatchRepository()
}
