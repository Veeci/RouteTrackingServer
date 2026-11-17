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

/** The side of a trip a user is on: the column a history query matches them against. */
enum class TripParty { GUEST, DRIVER }

interface TripRepository {
    suspend fun insert(trip: Trip)

    suspend fun find(id: TripId): Trip?

    /** Up to [limit] trips in [status], oldest first, starting after [after]. */
    suspend fun findByStatus(
        status: TripStatus,
        after: TripCursor?,
        limit: Int,
    ): List<Trip>

    /**
     * Up to [limit] trips of [user] as [party], in every status, newest first: by createdAt, then id, both
     * descending. The page starts after [before], the last trip of the previous page.
     */
    suspend fun findHistory(
        user: UserId,
        party: TripParty,
        before: TripCursor?,
        limit: Int,
    ): List<Trip>

    /** Every trip that has a driver and is not finished yet (ACCEPTED, ARRIVED, IN_PROGRESS). */
    suspend fun findActive(): List<Trip>

    /** For every driver who ever had a trip, the trip that changed stage last (phase 11: the fleet rebuild). */
    suspend fun findLatestPerDriver(): List<Trip>

    /** The trip that [driver] is assigned to and that is not finished yet, if any. */
    suspend fun findActiveForDriver(driver: UserId): Trip?

    /**
     * Saves [trip] only if the stored trip still has [Trip.version]; the stored version becomes version + 1.
     * Returns false if someone saved the trip in between (nothing is changed then). Throws a DomainException
     * with DRIVER_ALREADY_ON_TRIP if the driver got another active trip in between.
     */
    suspend fun update(trip: Trip): Boolean
}
