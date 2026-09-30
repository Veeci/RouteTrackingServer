package veeci.practicing.rts.trip

import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRepository
import veeci.practicing.rts.trip.application.TripService

/** Module wiring at the context root may reference every layer: no violation expected. */
class CleanTripModule(
    val service: TripService,
    val repository: ExposedTripRepository,
)
