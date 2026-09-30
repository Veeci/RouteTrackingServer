package veeci.practicing.rts.live.application

import veeci.practicing.rts.trip.adapter.out.persistence.TripsTable
import veeci.practicing.rts.trip.application.api.TripQuery
import veeci.practicing.rts.trip.domain.event.TripStatusChanged

class LeakyLive(
    val table: TripsTable,
    val query: TripQuery,
    val event: TripStatusChanged,
)
