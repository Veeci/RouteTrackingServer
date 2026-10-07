package veeci.practicing.rts.trip.application.port.out

import veeci.practicing.rts.trip.domain.event.TripStatusChanged

/** Stores the trip context's events for delivery after the commit. Joins the caller's transaction. */
interface TripEventOutbox {
    suspend fun add(event: TripStatusChanged)
}
