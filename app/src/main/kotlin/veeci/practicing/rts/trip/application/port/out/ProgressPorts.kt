package veeci.practicing.rts.trip.application.port.out

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.MeasuredPolyline
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.progress.MatchState
import kotlin.time.Instant

/** Where the driver is heading: the guest (before the ride) or the trip's destination (during the ride). */
enum class ProgressTarget { PICKUP, DESTINATION }

/** How the remaining distance was measured. */
enum class ProgressBasis {
    /** A straight line to the target: the trip has no route for this stage. */
    STRAIGHT_LINE,

    /** Along the planned route, from where the driver's fix snaps onto it. */
    ROUTE,
}

data class DriverPosition(
    val tripId: TripId,
    val point: GeoPoint,
    val bearingDeg: Double?,
    val speedMps: Double?,
    val recordedAt: Instant,
)

data class TripProgress(
    val tripId: TripId,
    val target: ProgressTarget,
    val remainingM: Double,
    val etaS: Double,
    val basis: ProgressBasis = ProgressBasis.STRAIGHT_LINE,
    /** The version of the route [remainingM] is measured on; null for [ProgressBasis.STRAIGHT_LINE]. */
    val routeVersion: Int? = null,
)

/** Sends position and progress to whoever shows them live. Never waits. */
interface ProgressPublisher {
    fun publish(
        position: DriverPosition,
        progress: TripProgress,
    )
}

/** What progress remembers between two batches of one trip. */
data class ProgressState(
    /** The smoothed speed; null until the first speed sample. */
    val speedMps: Double?,
    val lastPoint: GeoPoint,
    val lastAt: Instant,
    /** The route the driver follows and where they are on it; null when the trip has none for this stage. */
    val route: FollowedRoute? = null,
    /** When the last re-route was asked for (fix time), for the cooldown. Kept across route versions. */
    val lastRerouteAt: Instant? = null,
)

/**
 * A stored route, decoded once per version, with the driver's place on it. The progress state stores only its
 * version and the place; each instance keeps the decoded line in a cache (ExposedProgressStateStore).
 */
data class FollowedRoute(
    val kind: RouteKind,
    val version: Int,
    val line: MeasuredPolyline,
    val distanceM: Double,
    val durationS: Double,
    /** Null until the first fix is matched on this version. */
    val match: MatchState? = null,
    /** Fixes in a row that counted toward off-route on this version (see OffRouteRule). */
    val offRouteCount: Int = 0,
) {
    fun isSameRoute(
        kind: RouteKind,
        version: Int,
    ) = this.kind == kind && this.version == version
}

/**
 * Progress state per trip. It is derived from the fixes: losing it makes the ETA less smooth for a few fixes and
 * makes the matcher search the route again, nothing worse. Since phase 10 it is in Postgres and joins the caller's
 * transaction, because the events of one trip may be handled on any instance.
 */
interface ProgressStateStore {
    suspend fun get(trip: TripId): ProgressState?

    suspend fun put(
        trip: TripId,
        state: ProgressState,
    )

    suspend fun remove(trip: TripId)
}
