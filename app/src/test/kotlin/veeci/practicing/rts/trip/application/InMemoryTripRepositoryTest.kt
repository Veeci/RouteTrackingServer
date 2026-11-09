package veeci.practicing.rts.trip.application

import veeci.practicing.rts.testing.trip.InMemoryTripRepository
import veeci.practicing.rts.testing.trip.TripRepositoryContract

/** The in-memory repository that the service tests use passes the same contract as the Postgres one. */
class InMemoryTripRepositoryTest : TripRepositoryContract() {
    override val trips = InMemoryTripRepository()
}
