package veeci.practicing.rts.trip.application.port.out

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.domain.TripId
import kotlin.time.Instant

/** Where the driver is heading: the guest (before the ride) or the trip's destination (during the ride). */
enum class ProgressTarget { PICKUP, DESTINATION }

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
)

/** Progress state per trip. It only makes the ETA smoother, so losing it (a restart) is harmless. */
interface ProgressStateStore {
    fun get(trip: TripId): ProgressState?

    fun put(
        trip: TripId,
        state: ProgressState,
    )

    fun remove(trip: TripId)
}
