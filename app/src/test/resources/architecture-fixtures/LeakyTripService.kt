package veeci.practicing.rts.trip.application

import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRepository
import veeci.practicing.rts.trip.domain.Trip

class LeakyTripService(
    val repository: ExposedTripRepository,
    val trip: Trip,
)
