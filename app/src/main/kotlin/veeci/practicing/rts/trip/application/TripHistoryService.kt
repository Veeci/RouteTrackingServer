package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.trip.application.api.TripSnapshot
import veeci.practicing.rts.trip.application.port.out.TripCursor
import veeci.practicing.rts.trip.application.port.out.TripParty
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.application.port.out.TripSummaryRepository
import veeci.practicing.rts.trip.domain.StageTimes
import veeci.practicing.rts.trip.domain.TripError

/** One trip in a user's history. It never holds the track: that is one request per trip (phase 8c). */
data class TripHistoryItem(
    val trip: TripSnapshot,
    val stages: StageTimes,
    /** The ride distance from the trip's summary; null until the trip is completed and summarized. */
    val rideDistanceM: Double?,
)

data class TripHistoryPage(
    val items: List<TripHistoryItem>,
    /** Where the next page starts; null on the last page. */
    val next: TripCursor?,
)

/** A user's own trips, newest first (phase 8b). */
class TripHistoryService(
    private val trips: TripRepository,
    private val summaries: TripSummaryRepository,
    private val transactions: TransactionRunner,
) {
    /**
     * The trips where [actor] is the guest (a guest) or the driver (a driver). An admin has no trips of their own
     * and gets TRIP_ACTION_FORBIDDEN: a list of every trip belongs to the admin dashboard (phase 11).
     */
    suspend fun list(
        actor: Actor,
        before: TripCursor?,
        limit: Int,
    ): TripHistoryPage {
        val party =
            when (actor.role) {
                Role.GUEST -> TripParty.GUEST
                Role.DRIVER -> TripParty.DRIVER
                Role.ADMIN, Role.OPERATOR -> throw DomainException(TripError.TRIP_ACTION_FORBIDDEN, "Staff have no trip history")
            }
        return transactions.inTransaction {
            val found = trips.findHistory(actor.userId, party, before, limit + 1) // one more tells whether a next page exists
            val page = found.take(limit)
            // One query for the page's summaries, not one per trip.
            val distances = summaries.findAll(page.map { it.id }).mapValues { it.value.ride.distanceM }
            TripHistoryPage(
                page.map { TripHistoryItem(it.snapshot(), it.stages, distances[it.id]) },
                page.lastOrNull()?.takeIf { found.size > limit }?.let { TripCursor(it.createdAt, it.id) },
            )
        }
    }
}
