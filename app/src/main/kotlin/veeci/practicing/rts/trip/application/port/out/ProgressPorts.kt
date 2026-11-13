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
)

/**
 * A stored route, decoded once per version, with the driver's place on it. Kept in memory with the rest of the
 * progress state: after a restart it is decoded again and the matcher searches from the route's start.
 */
data class FollowedRoute(
    val kind: RouteKind,
    val version: Int,
    val line: MeasuredPolyline,
    val distanceM: Double,
    val durationS: Double,
    /** Null until the first fix is matched on this version. */
    val match: MatchState? = null,
) {
    fun isSameRoute(
        kind: RouteKind,
        version: Int,
    ) = this.kind == kind && this.version == version
}

/**
 * Progress state per trip. It is derived from the fixes: losing it (a restart) makes the ETA less smooth for a few
 * fixes and makes the matcher search the route again, nothing worse.
 */
interface ProgressStateStore {
    fun get(trip: TripId): ProgressState?

    fun put(
        trip: TripId,
        state: ProgressState,
    )

    fun remove(trip: TripId)
}
