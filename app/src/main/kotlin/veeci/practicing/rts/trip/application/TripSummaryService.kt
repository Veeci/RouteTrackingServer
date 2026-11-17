package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.tracking.application.api.DriverTrack
import veeci.practicing.rts.tracking.application.api.TrackCursor
import veeci.practicing.rts.trip.application.port.out.TripMomentRepository
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.application.port.out.TripSummaryRepository
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.RouteReason
import veeci.practicing.rts.trip.domain.TimeWindow
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.approachWindow
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.hasExactRide
import veeci.practicing.rts.trip.domain.rideWindow
import veeci.practicing.rts.trip.domain.summary.LegSummarizer
import veeci.practicing.rts.trip.domain.summary.LegSummary
import veeci.practicing.rts.trip.domain.summary.TrackSample
import veeci.practicing.rts.trip.domain.summary.TripSummary
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.toKotlinInstant

/**
 * Summaries of completed trips (phase 8a). [summarize] runs when a trip completes, inside the outbox relay's
 * transaction; the reads below run in their own.
 */
class TripSummaryService(
    private val trips: TripRepository,
    private val routes: TripRouteRepository,
    private val moments: TripMomentRepository,
    private val summaries: TripSummaryRepository,
    private val track: DriverTrack,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    /** How long GPS points are kept (phase 10): a trip that ended before that cannot be summarized again. */
    private val retention: Duration = Duration.INFINITE,
) {
    /**
     * Computes the trip's summary and stores it, replacing an earlier one, so a second delivery of the completion
     * changes nothing. Returns null, and stores nothing, for a trip that is missing or not COMPLETED.
     */
    suspend fun summarize(tripId: TripId): TripSummary? =
        transactions.inTransaction {
            val trip = trips.find(tripId)?.takeIf { it.status == TripStatus.COMPLETED } ?: return@inTransaction null
            val driver = checkNotNull(trip.driverId) { "A completed trip has a driver" }
            val ride = checkNotNull(trip.rideWindow()) { "A completed trip has a ride window" }
            val tripRoutes = routes.findAll(trip.id)
            TripSummary(
                tripId = trip.id,
                ride = leg(driver, ride),
                approach = trip.approachWindow()?.let { leg(driver, it) },
                plannedDistanceM = tripRoutes.firstOrNull { it.kind == RouteKind.TRIP && it.version == 1 }?.distanceM,
                offRouteCount = moments.countOffRoute(trip.id),
                rerouteCount = tripRoutes.count { it.reason == RouteReason.OFF_ROUTE },
                rejected = track.rejections(driver, ride.from, ride.to),
                approximate = !trip.hasExactRide,
                computedAt = clock.now(),
            ).also { summaries.save(it) }
        }

    /**
     * The summary for [actor]: the guest, the assigned driver or an admin. Anyone else gets TRIP_NOT_FOUND, the
     * same answer as for a trip that does not exist. A guest never gets the approach leg.
     */
    suspend fun get(
        tripId: TripId,
        actor: Actor,
    ): TripSummary =
        transactions.inTransaction {
            val trip = visibleTrip(tripId, actor)
            val summary =
                summaries.find(trip.id)
                    ?: throw DomainException(TripError.SUMMARY_NOT_READY, "Trip ${tripId.value} has no summary yet")
            if (actor.role == Role.GUEST) summary.copy(approach = null) else summary
        }

    /** An admin computes the summary again, for example after a fix in the calculation. */
    suspend fun recompute(
        tripId: TripId,
        actor: Actor,
    ): TripSummary {
        if (actor.role != Role.ADMIN) throw DomainException(TripError.TRIP_ACTION_FORBIDDEN, "Only an admin may compute a summary again")
        val trip = transactions.inTransaction { visibleTrip(tripId, actor) }
        checkPointsKept(trip)
        return summarize(trip.id) ?: throw DomainException(TripError.TRIP_NOT_ENDED, "Only a completed trip has a summary")
    }

    /** The points are gone after the retention period: a new summary would replace the real one with zeros. */
    private fun checkPointsKept(trip: Trip) {
        if ((trip.stages.endedAt ?: trip.updatedAt) < clock.instant().toKotlinInstant() - retention) {
            throw DomainException(TripError.TRACK_EXPIRED, "The GPS points of trip ${trip.id.value} are deleted")
        }
    }

    private suspend fun visibleTrip(
        tripId: TripId,
        actor: Actor,
    ): Trip =
        trips.find(tripId)?.takeIf { it.isVisibleTo(actor) && it.status != TripStatus.REQUESTED }
            ?: throw DomainException(TripError.TRIP_NOT_FOUND, "No trip ${tripId.value}")

    /** Reads the driver's track in [window] page by page; only one page is in memory at a time. */
    private suspend fun leg(
        driver: UserId,
        window: TimeWindow,
    ): LegSummary {
        val summarizer = LegSummarizer(window.from, window.to)
        var after: TrackCursor? = null
        do {
            val page = track.fixes(driver, window.from, window.to, after, PAGE_SIZE)
            page.forEach { summarizer.add(TrackSample(GeoPoint(it.lat, it.lng), it.recordedAt, it.speedMps ?: it.derivedSpeedMps)) }
            after = page.lastOrNull()?.cursor
        } while (page.size == PAGE_SIZE)
        return summarizer.result()
    }

    private companion object {
        const val PAGE_SIZE = 1_000
    }
}
