package veeci.practicing.rts.trip.application.port.out

import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripRoute

/** The `trip_routes` table. Runs inside the caller's transaction. */
interface TripRouteRepository {
    suspend fun insert(route: TripRoute)

    /** The newest version of the trip's route of [kind], or null when none was planned. */
    suspend fun findLatest(
        tripId: TripId,
        kind: RouteKind,
    ): TripRoute?
}
