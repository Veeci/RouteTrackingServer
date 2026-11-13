package veeci.practicing.rts.trip.application.port.out

import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripRoute

/** The `trip_routes` table. Runs inside the caller's transaction. */
interface TripRouteRepository {
    suspend fun insert(route: TripRoute)

    /**
     * Inserts [route] unless its (trip, kind, version) is taken; false when it was. Two re-routes of one version
     * race here: one inserts, the other gets false and drops its result. It never fails the transaction.
     */
    suspend fun insertIfAbsent(route: TripRoute): Boolean

    /** The newest version of the trip's route of [kind], or null when none was planned. */
    suspend fun findLatest(
        tripId: TripId,
        kind: RouteKind,
    ): TripRoute?
}
