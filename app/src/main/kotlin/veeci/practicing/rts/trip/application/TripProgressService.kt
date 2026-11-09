package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.trip.application.port.out.DriverPosition
import veeci.practicing.rts.trip.application.port.out.ProgressPublisher
import veeci.practicing.rts.trip.application.port.out.ProgressState
import veeci.practicing.rts.trip.application.port.out.ProgressStateStore
import veeci.practicing.rts.trip.application.port.out.ProgressTarget
import veeci.practicing.rts.trip.application.port.out.TripProgress
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripStatus
import kotlin.time.DurationUnit

/**
 * Turns the driver's accepted fixes into what the guest sees: where the driver is, how far the target is, and
 * when they arrive. For now the distance is a straight line (phase 7 measures it along the road).
 *
 * ETA = remaining distance ÷ smoothed speed. The speed is an exponentially weighted moving average,
 * s = α·v + (1 − α)·s_previous, so one fast or slow fix does not make the ETA jump. A driver standing still
 * would give an endless ETA, so the speed used is at least [minSpeedMps].
 */
class TripProgressService(
    private val trips: TripRepository,
    private val states: ProgressStateStore,
    private val publisher: ProgressPublisher,
    private val transactions: TransactionRunner,
    private val smoothing: Double = SMOOTHING,
    private val minSpeedMps: Double = MIN_SPEED_MPS,
) {
    suspend fun onFixesAccepted(event: FixesAccepted) {
        // No driver: a session from before drivers had accounts. No active trip: nobody is waiting for this driver.
        val trip = event.driverId?.let { activeTripOf(UserId(it)) } ?: return
        val before = states.get(trip.id)
        // The outbox delivers at least once and a retry may come late: only positions newer than the last one count.
        val fresh = event.positions.filter { before == null || it.recordedAt > before.lastAt }
        if (fresh.isEmpty()) return
        val state = fresh.drop(1).fold(before.next(fresh.first())) { state, position -> state.next(position) }
        states.put(trip.id, state)

        val latest = fresh.last()
        val (target, point) =
            if (trip.status ==
                TripStatus.IN_PROGRESS
            ) {
                ProgressTarget.DESTINATION to trip.destination
            } else {
                ProgressTarget.PICKUP to trip.pickup
            }
        val remainingM = Haversine.distance(latest.point, point).value
        val etaS = remainingM / maxOf(state.speedMps ?: 0.0, minSpeedMps)
        publisher.publish(
            DriverPosition(trip.id, latest.point, latest.bearingDeg, latest.speedMps, latest.recordedAt),
            TripProgress(trip.id, target, remainingM, etaS),
        )
    }

    private suspend fun activeTripOf(driver: UserId) = transactions.inTransaction { trips.findActiveForDriver(driver) }

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
                else -> smoothing * sample + (1 - smoothing) * previous
            }
        return ProgressState(speed, position.point, position.recordedAt)
    }

    private companion object {
        /** α: how much one new sample counts. */
        const val SMOOTHING = 0.3

        /** About walking speed: below it the driver is treated as "about to move again". */
        const val MIN_SPEED_MPS = 2.0
    }
}
