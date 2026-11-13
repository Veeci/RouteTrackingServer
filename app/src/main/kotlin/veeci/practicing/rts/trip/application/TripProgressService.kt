package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.shared.geo.MeasuredPolyline
import veeci.practicing.rts.shared.geo.PolylineCodec
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.trip.application.port.out.DriverPosition
import veeci.practicing.rts.trip.application.port.out.FollowedRoute
import veeci.practicing.rts.trip.application.port.out.ProgressBasis
import veeci.practicing.rts.trip.application.port.out.ProgressPublisher
import veeci.practicing.rts.trip.application.port.out.ProgressState
import veeci.practicing.rts.trip.application.port.out.ProgressStateStore
import veeci.practicing.rts.trip.application.port.out.ProgressTarget
import veeci.practicing.rts.trip.application.port.out.TripProgress
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripRoute
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.progress.MatchInput
import veeci.practicing.rts.trip.domain.progress.OffRouteRule
import veeci.practicing.rts.trip.domain.progress.RouteEta
import veeci.practicing.rts.trip.domain.progress.RouteMatch
import veeci.practicing.rts.trip.domain.progress.RouteMatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

/**
 * Turns the driver's accepted fixes into what the guest sees: where the driver is, how far the target is, and
 * when they arrive.
 *
 * When the trip has a route for its stage (`trip_routes`, see [RouteKind.followedIn]), each fix is snapped onto it
 * by [RouteMatcher], the distance left is measured along the road and the ETA comes from [RouteEta]. Without one
 * (the APPROACH route arrives in phase 7c) the distance is a straight line and ETA = remaining distance ÷ speed.
 *
 * On the route, [OffRouteRule] counts fixes far beside it. When the driver is off-route and the last re-route is
 * at least [ProgressRules.rerouteCooldown] old, a [RerouteRequest] goes to [rerouter], which plans outside this
 * transaction. The new version then arrives with a later batch and matching starts again on it.
 *
 * The speed is an exponentially weighted moving average, s = α·v + (1 − α)·s_previous, so one fast or slow fix does
 * not make the ETA jump. A driver standing still would give an endless straight-line ETA, so the speed used there
 * is at least [ProgressRules.minSpeedMps].
 */
class TripProgressService(
    private val trips: TripRepository,
    private val routes: TripRouteRepository,
    private val states: ProgressStateStore,
    private val publisher: ProgressPublisher,
    private val transactions: TransactionRunner,
    private val rerouter: Rerouter,
    private val rules: ProgressRules = ProgressRules(),
) {
    suspend fun onFixesAccepted(event: FixesAccepted) {
        // No driver: a session from before drivers had accounts. No active trip: nobody is waiting for this driver.
        val (trip, stored) = event.driverId?.let { load(UserId(it)) } ?: return
        val before = states.get(trip.id)
        // The outbox delivers at least once and a retry may come late: only positions newer than the last one count.
        val fresh = event.positions.filter { before == null || it.recordedAt > before.lastAt }
        if (fresh.isEmpty()) return

        val (target, point) =
            if (trip.status == TripStatus.IN_PROGRESS) {
                ProgressTarget.DESTINATION to trip.destination
            } else {
                ProgressTarget.PICKUP to trip.pickup
            }

        // Decode the route once per version; a new version (or stage) starts matching from scratch.
        var step = RouteStep(stored?.let { r -> before?.route?.takeIf { it.isSameRoute(r.kind, r.version) } ?: r.toFollowed() })
        var lastRerouteAt = before?.lastRerouteAt
        var state = before
        for (position in fresh) {
            state = state.next(position)
            step = step.follow(position, trip.id, point, lastRerouteAt)
            lastRerouteAt = step.reroutedAt ?: lastRerouteAt
        }
        val current = checkNotNull(state).copy(route = step.route, lastRerouteAt = lastRerouteAt)
        states.put(trip.id, current)

        val latest = fresh.last()
        val route = step.route
        val match = step.match
        val progress =
            if (route != null && match != null) {
                val etaS = rules.eta.etaS(route.distanceM, route.durationS, match.remainingM, current.speedMps)
                TripProgress(trip.id, target, match.remainingM, etaS, ProgressBasis.ROUTE, route.version)
            } else {
                val remainingM = Haversine.distance(latest.point, point).value
                TripProgress(trip.id, target, remainingM, remainingM / maxOf(current.speedMps ?: 0.0, rules.minSpeedMps))
            }
        publisher.publish(DriverPosition(trip.id, latest.point, latest.bearingDeg, latest.speedMps, latest.recordedAt), progress)
    }

    /** Where the driver is on the route after a fix, and when this fix asked for a re-route (null if it did not). */
    private data class RouteStep(
        val route: FollowedRoute?,
        val match: RouteMatch? = null,
        val reroutedAt: Instant? = null,
    )

    /** Matches [position] on the route, counts it toward off-route, and asks for a re-route when it is time. */
    private fun RouteStep.follow(
        position: FixesAccepted.Position,
        tripId: TripId,
        target: GeoPoint,
        lastRerouteAt: Instant?,
    ): RouteStep {
        val r = route ?: return RouteStep(null)
        val m = rules.matcher.match(r.line, r.match, MatchInput(position.point, position.speedMps, position.bearingDeg))
        val count = rules.offRoute.count(r.offRouteCount, m.projection.crossTrackM, position.accuracyM)
        val asked =
            rules.offRoute.isOffRoute(count) &&
                (lastRerouteAt == null || position.recordedAt - lastRerouteAt >= rules.rerouteCooldown) &&
                rerouter.request(RerouteRequest(tripId, r.kind, r.version, position.point, target, position.recordedAt))
        return RouteStep(
            r.copy(match = m.state, offRouteCount = if (asked) 0 else count),
            m,
            if (asked) position.recordedAt else null,
        )
    }

    /** The driver's active trip and the newest route of the kind its stage follows, in one transaction. */
    private suspend fun load(driver: UserId): Pair<Trip, TripRoute?>? =
        transactions.inTransaction {
            val trip = trips.findActiveForDriver(driver) ?: return@inTransaction null
            trip to RouteKind.followedIn(trip.status)?.let { routes.findLatest(trip.id, it) }
        }

    /** The trip is finished: its state is not needed any more. */
    fun forget(trip: TripId) = states.remove(trip)

    /** Adds one position: the speed the device measured, or else the speed implied by the distance from the last one. */
    private fun ProgressState?.next(position: FixesAccepted.Position): ProgressState {
        val sample =
            position.speedMps ?: this?.let {
                val seconds = (position.recordedAt - it.lastAt).toDouble(DurationUnit.SECONDS)
                if (seconds > 0) Haversine.distance(it.lastPoint, position.point).value / seconds else null
            }
        val previous = this?.speedMps
        val speed =
            when {
                sample == null -> previous

                previous == null -> sample

                // the first sample starts the average
                else -> rules.smoothing * sample + (1 - rules.smoothing) * previous
            }
        return ProgressState(speed, position.point, position.recordedAt)
    }

    private fun TripRoute.toFollowed() =
        FollowedRoute(kind, version, MeasuredPolyline(PolylineCodec.decode(polyline)), distanceM, durationS)
}

/** The rules and tuning values of [TripProgressService]. Tests change one value; production uses the defaults. */
data class ProgressRules(
    val matcher: RouteMatcher = RouteMatcher(),
    val eta: RouteEta = RouteEta(),
    val offRoute: OffRouteRule = OffRouteRule(),
    /** At most one re-route per trip in this time, so a driver who keeps leaving the route gets no flood. */
    val rerouteCooldown: Duration = 30.seconds,
    /** α: how much one new speed sample counts. */
    val smoothing: Double = 0.3,
    /** About walking speed: below it the driver is treated as "about to move again" (straight-line ETA only). */
    val minSpeedMps: Double = 2.0,
)
