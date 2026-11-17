package veeci.practicing.rts.trip.application

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.DouglasPeucker
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.tracking.application.api.DriverTrack
import veeci.practicing.rts.tracking.application.api.TrackCursor
import veeci.practicing.rts.tracking.application.api.TrackPoint
import veeci.practicing.rts.trip.application.port.out.TripMomentRepository
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.domain.TimeWindow
import veeci.practicing.rts.trip.domain.TimelineEntry
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripLeg
import veeci.practicing.rts.trip.domain.TripTimeline
import veeci.practicing.rts.trip.domain.window
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.toKotlinInstant

/** One leg of an ended trip and the whole trip's timeline, for a replay (phase 8c). */
data class TripTrack(
    val tripId: TripId,
    val leg: TripLeg,
    /** The driver's accepted fixes in the leg's window, oldest first; simplified when asked. */
    val points: List<TrackPoint>,
    /** Every stage, moment and route version of the trip, whatever [leg] is. */
    val timeline: List<TimelineEntry>,
    /** The trip ended longer ago than the retention period, so [points] is empty for good (phase 10). */
    val pointsExpired: Boolean = false,
)

/** One leg of an ended trip for a file export (phase 8d): everything but the points is read already. */
class TrackExport(
    val tripId: TripId,
    val leg: TripLeg,
    /** The leg's window; null when the trip never had this leg. */
    val window: TimeWindow?,
    /** The whole trip's timeline, in time order. */
    val timeline: List<TimelineEntry>,
    /** The leg's fixes, oldest first, one page per emission. Cold: nothing is read until it is collected. */
    val pages: Flow<List<TrackPoint>>,
)

/** The recorded track and timeline of ended trips (phases 8c and 8d). */
class TripTrackService(
    private val trips: TripRepository,
    private val moments: TripMomentRepository,
    private val routes: TripRouteRepository,
    private val track: DriverTrack,
    private val transactions: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
    /** How long GPS points are kept (phase 10). A trip that ended before that has no points any more. */
    private val retention: Duration = Duration.INFINITE,
) {
    /**
     * The track of [leg] for [actor]: the guest, the assigned driver or an admin. Anyone else gets TRIP_NOT_FOUND,
     * the same answer as for a trip that does not exist. A trip that has not ended gets TRIP_NOT_ENDED, and a guest
     * who asks for the approach gets TRIP_ACTION_FORBIDDEN: where the driver drove before the pickup is theirs.
     *
     * [simplifyM] above 0 drops points with Douglas–Peucker at that tolerance in metres; the timeline is never
     * simplified. The whole leg is read into memory, because simplification needs every point before it can
     * drop one. A long trip is still small: 2 hours at one fix a second is 7 200 points.
     */
    suspend fun track(
        tripId: TripId,
        actor: Actor,
        leg: TripLeg,
        simplifyM: Double = 0.0,
    ): TripTrack {
        require(simplifyM in 0.0..MAX_SIMPLIFY_M) { "simplifyM must be 0 to $MAX_SIMPLIFY_M, was $simplifyM" }
        return transactions.inTransaction {
            val trip = readable(tripId, actor, leg)
            val driver = trip.driverId
            val window = trip.window(leg)
            val expired = trip.trackExpired()
            // Already inside a transaction: every page joins it. An expired track is not read at all: the retention
            // job may have deleted part of it, and a half track would look like a GPS problem.
            val points =
                if (driver == null || window == null || expired) emptyList() else pages(driver, window) { it() }.toList().flatten()
            TripTrack(
                trip.id,
                leg,
                DouglasPeucker.simplify(points, simplifyM) { GeoPoint(it.lat, it.lng) },
                TripTimeline.of(trip, moments.findAll(trip.id), routes.findAll(trip.id)),
                pointsExpired = expired,
            )
        }
    }

    /**
     * The same leg for a file export (phase 8d). The checks and the timeline run now, in one short transaction,
     * so the route can still answer 404, 403 or 409 before it sends a byte. The points come later from
     * [TrackExport.pages], a cold flow: each page of 1 000 fixes is read in its own transaction when the writer
     * asks for it, so a slow download holds no database connection between pages.
     */
    suspend fun export(
        tripId: TripId,
        actor: Actor,
        leg: TripLeg,
    ): TrackExport {
        val (trip, timeline) =
            transactions.inTransaction {
                val trip = readable(tripId, actor, leg)
                if (trip.trackExpired()) {
                    throw DomainException(
                        TripError.TRACK_EXPIRED,
                        "The GPS points of trip ${trip.id.value} are deleted",
                    )
                }
                trip to TripTimeline.of(trip, moments.findAll(trip.id), routes.findAll(trip.id))
            }
        val driver = trip.driverId
        val window = trip.window(leg)
        val pages = if (driver == null || window == null) emptyFlow() else pages(driver, window) { transactions.inTransaction(it) }
        return TrackExport(trip.id, leg, window, timeline, pages)
    }

    private suspend fun readable(
        tripId: TripId,
        actor: Actor,
        leg: TripLeg,
    ): Trip =
        trips
            .find(tripId)
            ?.takeIf { it.isPartyOrAdmin(actor) }
            ?.also { it.checkReadable(actor, leg) }
            ?: throw DomainException(TripError.TRIP_NOT_FOUND, "No trip ${tripId.value}")

    /** Only an ended trip has a track, and only the driver and admins see the approach. */
    private fun Trip.checkReadable(
        actor: Actor,
        leg: TripLeg,
    ) {
        if (!status.isTerminal) throw DomainException(TripError.TRIP_NOT_ENDED, "Trip ${id.value} is $status")
        if (leg == TripLeg.APPROACH && actor.role == Role.GUEST) {
            throw DomainException(TripError.TRIP_ACTION_FORBIDDEN, "Only the driver and admins see the approach")
        }
    }

    /** The trip ended before the retention period: an old trip without stage times counts from its last change. */
    private fun Trip.trackExpired(): Boolean = (stages.endedAt ?: updatedAt) < clock.instant().toKotlinInstant() - retention

    /** The trip's own people and admins. Unlike [Trip.isVisibleTo], a driver does not see someone else's open trip. */
    private fun Trip.isPartyOrAdmin(actor: Actor): Boolean =
        when (actor.role) {
            Role.ADMIN -> true
            Role.GUEST -> actor.userId == guestId
            Role.DRIVER -> actor.userId == driverId
        }

    /**
     * The fixes of [driver] in [window], one page per emission, oldest first; an empty page is never emitted.
     * [read] runs each page's query: directly inside a caller's transaction, or in a transaction of its own.
     */
    private fun pages(
        driver: UserId,
        window: TimeWindow,
        read: suspend (suspend () -> List<TrackPoint>) -> List<TrackPoint>,
    ): Flow<List<TrackPoint>> =
        flow {
            var after: TrackCursor? = null
            do {
                val cursor = after
                val page = read { track.fixes(driver, window.from, window.to, cursor, PAGE_SIZE) }
                if (page.isNotEmpty()) emit(page)
                after = page.lastOrNull()?.cursor
            } while (page.size == PAGE_SIZE)
        }

    companion object {
        /** The largest simplify tolerance. Above it the line no longer follows the streets. */
        const val MAX_SIMPLIFY_M = 50.0
        private const val PAGE_SIZE = 1_000
    }
}
