package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.db.TransactionRunner
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
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.RouteReason
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
    private val routes: TripRouteRepository,
    private val states: ProgressStateStore,
    private val publisher: ProgressPublisher,
    private val transactions: TransactionRunner,
    private val rerouter: Rerouter,
    private val rules: ProgressRules = ProgressRules(),
) {
    /** The driver of [trip], their active trip, moved. [DriverMovementService] reads the trip and calls this. */
    suspend fun onFixesAccepted(
        event: FixesAccepted,
        trip: Trip,
    ) {
        val stored = routeOf(trip)
        val before = states.get(trip.id)
        // The outbox delivers at least once and a retry may come late: only positions newer than the last one count.
        val fresh = event.positions.filter { before == null || it.recordedAt > before.lastAt }
        if (fresh.isEmpty()) return

        val (target, point) = trip.target()

        // Decode the route once per version; a new version (or stage) starts matching from scratch.
        var step = RouteStep(stored?.let { r -> before?.route?.takeIf { it.isSameRoute(r.kind, r.version) } ?: r.toFollowed() })
        var lastRerouteAt = before?.lastRerouteAt
        var state = before
        for (position in fresh) {
            state = state.next(position)
            step = step.follow(position, trip.id, point, lastRerouteAt)
            lastRerouteAt = step.reroutedAt ?: lastRerouteAt
        }
        val latest = fresh.last()
        if (stored == null && trip.status == TripStatus.ACCEPTED) lastRerouteAt = askForApproach(trip, latest, lastRerouteAt)
        val current = checkNotNull(state).copy(route = step.route, lastRerouteAt = lastRerouteAt)
        states.put(trip.id, current)

        val speedMps = maxOf(current.speedMps ?: 0.0, rules.minSpeedMps)
        val progress = progressOf(trip.id, target, step, current.speedMps) ?: straightLine(trip.id, target, latest.point, point, speedMps)
        publisher.publish(DriverPosition(trip.id, latest.point, latest.bearingDeg, latest.speedMps, latest.recordedAt), progress)
    }

    /** Progress along the route; null when the trip has no route for this stage. */
    private fun progressOf(
        tripId: TripId,
        target: ProgressTarget,
        step: RouteStep,
        speedMps: Double?,
    ): TripProgress? {
        val route = step.route
        val match = step.match
        if (route == null || match == null) return null
        val etaS = rules.eta.etaS(route.distanceM, route.durationS, match.remainingM, speedMps)
        return TripProgress(tripId, target, match.remainingM, etaS, ProgressBasis.ROUTE, route.version)
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
                lastRerouteAt.cooledDownAt(position.recordedAt) &&
                rerouter.request(RerouteRequest(tripId, r.kind, r.version, position.point, target, position.recordedAt))
        return RouteStep(
            r.copy(match = m.state, offRouteCount = if (asked) 0 else count),
            m,
            if (asked) position.recordedAt else null,
        )
    }

    /**
     * No APPROACH route yet: asks for one from where the driver is now (phase 7c), and returns the new time of the
     * last request. Until the route is stored the progress stays a straight line; a failed plan is asked again
     * after the cooldown.
     */
    private fun askForApproach(
        trip: Trip,
        latest: FixesAccepted.Position,
        lastRerouteAt: Instant?,
    ): Instant? {
        if (!lastRerouteAt.cooledDownAt(latest.recordedAt)) return lastRerouteAt
        val first = RerouteRequest(trip.id, RouteKind.APPROACH, 0, latest.point, trip.pickup, latest.recordedAt, RouteReason.INITIAL)
        return if (rerouter.request(first)) latest.recordedAt else lastRerouteAt
    }

    private fun Instant?.cooledDownAt(now: Instant) = this == null || now - this >= rules.rerouteCooldown

    /** The newest route of the kind the trip's stage follows; null for a stage that follows none. */
    private suspend fun routeOf(trip: Trip): TripRoute? =
        RouteKind.followedIn(trip.status)?.let { kind -> transactions.inTransaction { routes.findLatest(trip.id, kind) } }

    /** The trip is finished: its state is not needed any more. */
    suspend fun forget(trip: TripId) = states.remove(trip)

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

/** Where the driver is heading: the pickup before the ride, the destination during it. */
private fun Trip.target(): Pair<ProgressTarget, GeoPoint> =
    if (status == TripStatus.IN_PROGRESS) ProgressTarget.DESTINATION to destination else ProgressTarget.PICKUP to pickup

/** Progress in a straight line: remaining distance ÷ [speedMps]. */
private fun straightLine(
    tripId: TripId,
    target: ProgressTarget,
    from: GeoPoint,
    to: GeoPoint,
    speedMps: Double,
): TripProgress {
    val remainingM = Haversine.distance(from, to).value
    return TripProgress(tripId, target, remainingM, remainingM / speedMps)
}

private fun TripRoute.toFollowed() = FollowedRoute(kind, version, MeasuredPolyline(PolylineCodec.decode(polyline)), distanceM, durationS)
