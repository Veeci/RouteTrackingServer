package veeci.practicing.rts.trip.application

import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.routing.application.api.RouteMetric
import veeci.practicing.rts.routing.application.api.RoutePlanner
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.PolylineCodec
import veeci.practicing.rts.trip.application.port.out.TripEventOutbox
import veeci.practicing.rts.trip.application.port.out.TripMomentRepository
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.RouteReason
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripRoute
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripRouteChanged
import java.time.Clock
import kotlin.time.Instant

/** The driver left version [baseVersion] of the trip's [kind] route at [from]; plan again from there to [to]. */
data class RerouteRequest(
    val tripId: TripId,
    val kind: RouteKind,
    val baseVersion: Int,
    val from: GeoPoint,
    val to: GeoPoint,
    /** When the fix that made the driver off-route was recorded. */
    val at: Instant,
)

/** Takes a re-route request without waiting. False when it could not be queued. */
fun interface Rerouter {
    fun request(request: RerouteRequest): Boolean
}

/** Carries out one re-route: plans and stores it. [TripRouteService] does; [RerouteWorker] calls it. */
fun interface RerouteHandler {
    suspend fun reroute(request: RerouteRequest): RerouteOutcome
}

enum class RerouteOutcome {
    /** A new version is stored, with the OffRoute moment and the TripRouteChanged event. */
    REROUTED,

    /** No route could be planned: the old route stays, only the OffRoute moment is stored. */
    FAILED,

    /** The request is out of date (the trip moved on, or another re-route stored the next version): nothing stored. */
    DROPPED,
}

/**
 * Plans a new route for a driver who left the old one, and stores it as the next version.
 *
 * The plan runs first, outside the transaction: it is up to 500 ms of CPU work, and no connection waits idle
 * meanwhile. The transaction then checks that the request still holds: the trip is in a stage that follows a
 * route of this kind, and the newest version is still [RerouteRequest.baseVersion]. The insert of the next version
 * cannot fail the transaction (`ON CONFLICT DO NOTHING`), so of two re-routes for one version, one wins and the
 * other stores nothing.
 */
class TripRouteService(
    private val trips: TripRepository,
    private val routes: TripRouteRepository,
    private val moments: TripMomentRepository,
    private val outbox: TripEventOutbox,
    private val planner: RoutePlanner,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) : RerouteHandler {
    private val log = LoggerFactory.getLogger(TripRouteService::class.java)

    override suspend fun reroute(request: RerouteRequest): RerouteOutcome {
        val planned =
            try {
                planner.plan(request.from, request.to, RouteMetric.DISTANCE)
            } catch (e: DomainException) {
                // No road near the driver, no path, or the planner is busy: keep the old route and try after the cooldown.
                log.info("No new route for trip {}: {}", request.tripId.value, e.code)
                null
            }
        return transactions.inTransaction {
            val trip = trips.find(request.tripId)
            val newest = trip?.let { routes.findLatest(it.id, request.kind) }?.version ?: 0
            if (trip == null || RouteKind.followedIn(trip.status) != request.kind || newest != request.baseVersion) {
                return@inTransaction RerouteOutcome.DROPPED
            }
            val route =
                planned?.let {
                    TripRoute(
                        trip.id,
                        request.kind,
                        request.baseVersion + 1,
                        PolylineCodec.encode(it.polyline),
                        it.distanceM,
                        it.durationS,
                        clock.now(),
                        RouteReason.OFF_ROUTE,
                    )
                }
            if (route != null && !routes.insertIfAbsent(route)) return@inTransaction RerouteOutcome.DROPPED

            val offRoute = TripMoment.OffRoute(trip.id.value, trip.guestId.value, trip.status, request.at)
            moments.append(offRoute)
            outbox.add(offRoute)
            if (route == null) {
                RerouteOutcome.FAILED
            } else {
                outbox.add(TripRouteChanged(route, trip.guestId.value, trip.driverId?.value))
                RerouteOutcome.REROUTED
            }
        }
    }
}
