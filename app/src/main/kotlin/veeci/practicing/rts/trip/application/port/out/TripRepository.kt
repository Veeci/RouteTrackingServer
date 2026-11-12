package veeci.practicing.rts.trip.application.port.out

import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripStatus
import kotlin.time.Instant

/** A position in a list of trips ordered by (createdAt, id): the next page starts after it. */
data class TripCursor(
    val createdAt: Instant,
    val id: TripId,
)

interface TripRepository {
    suspend fun insert(trip: Trip)

    suspend fun find(id: TripId): Trip?

    /** Up to [limit] trips in [status], oldest first, starting after [after]. */
    suspend fun findByStatus(
        status: TripStatus,
        after: TripCursor?,
        limit: Int,
    ): List<Trip>

    /** Every trip that has a driver and is not finished yet (ACCEPTED, ARRIVED, IN_PROGRESS). */
    suspend fun findActive(): List<Trip>

    /** The trip that [driver] is assigned to and that is not finished yet, if any. */
    suspend fun findActiveForDriver(driver: UserId): Trip?

    /**
     * Saves [trip] only if the stored trip still has [Trip.version]; the stored version becomes version + 1.
     * Returns false if someone saved the trip in between (nothing is changed then). Throws a DomainException
     * with DRIVER_ALREADY_ON_TRIP if the driver got another active trip in between.
     */
    suspend fun update(trip: Trip): Boolean
}
