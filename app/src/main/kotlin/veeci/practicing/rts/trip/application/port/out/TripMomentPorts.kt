package veeci.practicing.rts.trip.application.port.out

import veeci.practicing.rts.trip.domain.ArrivalState
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripMoment

/** The history of a trip's moments. Joins the caller's transaction. */
interface TripMomentRepository {
    suspend fun append(moment: TripMoment)
}

/** The arrival rules' state per trip. Joins the caller's transaction. */
interface ArrivalStateStore {
    /**
     * The trip's state, locked until the transaction ends, so a second batch of the same trip waits for this
     * one. A trip without state yet starts from an empty [ArrivalState].
     */
    suspend fun lock(trip: TripId): ArrivalState

    suspend fun save(
        trip: TripId,
        state: ArrivalState,
    )
}
