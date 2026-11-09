package veeci.practicing.rts.trip.adapter.out.live

import veeci.practicing.rts.live.application.api.DriverPositionUpdate
import veeci.practicing.rts.live.application.api.LiveUpdates
import veeci.practicing.rts.live.application.api.TripProgressUpdate
import veeci.practicing.rts.trip.application.port.out.DriverPosition
import veeci.practicing.rts.trip.application.port.out.ProgressPublisher
import veeci.practicing.rts.trip.application.port.out.TripProgress

/** Hands position and progress to the live context, through its public API. Position first, then progress. */
class LiveProgressPublisher(
    private val live: LiveUpdates,
) : ProgressPublisher {
    override fun publish(
        position: DriverPosition,
        progress: TripProgress,
    ) {
        live.publish(
            DriverPositionUpdate(position.tripId.value, position.point, position.bearingDeg, position.speedMps, position.recordedAt),
        )
        live.publish(
            TripProgressUpdate(progress.tripId.value, progress.target.name, progress.remainingM, progress.etaS, basis = "STRAIGHT_LINE"),
        )
    }
}
