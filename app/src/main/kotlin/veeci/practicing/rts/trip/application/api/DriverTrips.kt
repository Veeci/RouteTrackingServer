package veeci.practicing.rts.trip.application.api

import java.util.UUID

/**
 * Drivers' trips, the trip context's read API for the fleet view (phase 11). [TripSnapshot.updatedAt] is when the
 * trip last changed stage, the same time as that change's TripStatusChanged event. Joins the caller's transaction.
 */
interface DriverTrips {
    /** The driver's trips, newest first, at most [limit]. */
    suspend fun recentForDriver(
        driver: UUID,
        limit: Int,
    ): List<TripSnapshot>

    /** For every driver who ever had a trip, the one that changed stage last. */
    suspend fun latestPerDriver(): List<TripSnapshot>

    /** Trips with a driver that are not finished yet (ACCEPTED, ARRIVED, IN_PROGRESS). */
    suspend fun activeCount(): Int
}
