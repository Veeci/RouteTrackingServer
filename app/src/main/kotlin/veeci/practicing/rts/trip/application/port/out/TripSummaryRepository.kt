package veeci.practicing.rts.trip.application.port.out

import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.summary.TripSummary

/** The `trip_summaries` table (V13). Runs inside the caller's transaction. */
interface TripSummaryRepository {
    /** Inserts the summary, or replaces the trip's earlier one. */
    suspend fun save(summary: TripSummary)

    suspend fun find(tripId: TripId): TripSummary?

    /** The summaries of those of [tripIds] that have one, in one query. */
    suspend fun findAll(tripIds: Collection<TripId>): Map<TripId, TripSummary>
}
