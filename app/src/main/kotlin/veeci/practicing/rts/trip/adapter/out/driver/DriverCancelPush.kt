package veeci.practicing.rts.trip.adapter.out.driver

import veeci.practicing.rts.protocol.TripEvent
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.DriverLink
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.event.TripStatusChanged

/**
 * Tells the assigned driver that their trip was cancelled (phase 11d), over tracking's [DriverLink]: a `trip_event`
 * with `cancelledBy`, so the app can say whether the guest, the driver or an operator stopped it. Sent once and not
 * resent after a reconnect: the `trip_ended` command (device control) and `GET /api/v1/trips/{id}` both still tell
 * a driver who was offline.
 */
class DriverCancelPush(
    private val link: DriverLink,
) {
    suspend fun push(change: TripStatusChanged) {
        val driver = change.driverId ?: return
        if (change.to != TripStatus.CANCELLED) return
        link.send(
            UserId(driver),
            TripEvent(
                change.tripId.toString(),
                TripEvent.STATUS_CHANGED,
                change.to.name,
                change.at,
                cancelledBy = change.cancelledBy?.name,
            ),
        )
    }
}
