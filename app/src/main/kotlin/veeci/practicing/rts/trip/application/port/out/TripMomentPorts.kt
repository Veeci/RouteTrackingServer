package veeci.practicing.rts.trip.application.port.out

import veeci.practicing.rts.trip.domain.ArrivalState
import veeci.practicing.rts.trip.domain.BatteryState
import veeci.practicing.rts.trip.domain.PresenceState
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripMoment

/** The history of a trip's moments. Joins the caller's transaction. */
interface TripMomentRepository {
    suspend fun append(moment: TripMoment)
}

/**
 * Each rule's state per trip. Joins the caller's transaction. A lock lasts until that transaction ends, so a
 * second batch or status of the same trip waits for this one. A trip without state yet starts from an empty state.
 */
interface RuleStateStore {
    suspend fun lockArrival(trip: TripId): ArrivalState

    suspend fun saveArrival(
        trip: TripId,
        state: ArrivalState,
    )

    suspend fun lockBattery(trip: TripId): BatteryState

    suspend fun saveBattery(
        trip: TripId,
        state: BatteryState,
    )

    suspend fun lockPresence(trip: TripId): PresenceState

    suspend fun savePresence(
        trip: TripId,
        state: PresenceState,
    )
}
