package veeci.practicing.rts.trip.application

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

/** One leg of an ended trip and the whole trip's timeline, for a replay (phase 8c). */
data class TripTrack(
    val tripId: TripId,
    val leg: TripLeg,
    /** The driver's accepted fixes in the leg's window, oldest first; simplified when asked. */
    val points: List<TrackPoint>,
    /** Every stage, moment and route version of the trip, whatever [leg] is. */
    val timeline: List<TimelineEntry>,
)

/** The recorded track and timeline of ended trips (phase 8c). */
class TripTrackService(
    private val trips: TripRepository,
    private val moments: TripMomentRepository,
    private val routes: TripRouteRepository,
    private val track: DriverTrack,
    private val transactions: TransactionRunner,
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
            val trip =
                trips.find(tripId)?.takeIf { it.isPartyOrAdmin(actor) }
                    ?: throw DomainException(TripError.TRIP_NOT_FOUND, "No trip ${tripId.value}")
            trip.checkReadable(actor, leg)
            val driver = trip.driverId
            val window = trip.window(leg)
            val points = if (driver == null || window == null) emptyList() else readAll(driver, window)
            TripTrack(
                trip.id,
                leg,
                DouglasPeucker.simplify(points, simplifyM) { GeoPoint(it.lat, it.lng) },
                TripTimeline.of(trip, moments.findAll(trip.id), routes.findAll(trip.id)),
            )
        }
    }

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

    /** The trip's own people and admins. Unlike [Trip.isVisibleTo], a driver does not see someone else's open trip. */
    private fun Trip.isPartyOrAdmin(actor: Actor): Boolean =
        when (actor.role) {
            Role.ADMIN -> true
            Role.GUEST -> actor.userId == guestId
            Role.DRIVER -> actor.userId == driverId
        }

    private suspend fun readAll(
        driver: UserId,
        window: TimeWindow,
    ): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        var after: TrackCursor? = null
        do {
            val page = track.fixes(driver, window.from, window.to, after, PAGE_SIZE)
            points += page
            after = page.lastOrNull()?.cursor
        } while (page.size == PAGE_SIZE)
        return points
    }

    companion object {
        /** The largest simplify tolerance. Above it the line no longer follows the streets. */
        const val MAX_SIMPLIFY_M = 50.0
        private const val PAGE_SIZE = 1_000
    }
}
