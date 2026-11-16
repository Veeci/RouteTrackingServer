package veeci.practicing.rts.trip.adapter.out.driver

import veeci.practicing.rts.protocol.RouteUpdated
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.DriverLink
import veeci.practicing.rts.trip.application.TripRouteService
import veeci.practicing.rts.trip.domain.RouteReason
import veeci.practicing.rts.trip.domain.TripRoute
import veeci.practicing.rts.trip.domain.event.TripRouteChangedV1
import java.util.UUID

/**
 * Sends the driver the route they follow (phase 7c), over tracking's [DriverLink]: when it changes, and again
 * right after every `welcome` while the driver has an active trip. A route is not acked; the resend after each
 * connect is what makes a driver who was offline catch up. The latest route wins, so a missed older one does not
 * matter.
 */
class DriverRoutePush(
    private val link: DriverLink,
    private val routes: TripRouteService,
) {
    /** Registers with the driver sockets. Called once at startup. */
    fun start() {
        link.onConnected { driver -> routes.currentRouteOf(driver)?.let { link.send(driver, it.toMessage()) } }
    }

    /** A route changed (from the outbox): send it if the driver is connected here. */
    suspend fun push(change: TripRouteChangedV1) {
        val driver = change.driverId ?: return
        link.send(
            UserId(UUID.fromString(driver)),
            RouteUpdated(
                change.tripId,
                change.kind,
                change.version,
                change.polyline,
                change.distanceM,
                change.durationS,
                change.reason.lowercase(),
            ),
        )
    }

    // After a reconnect the reason is why this version exists (initial or off_route).
    private fun TripRoute.toMessage() =
        RouteUpdated(
            tripId.value.toString(),
            kind.name,
            version,
            polyline,
            distanceM,
            durationS,
            (if (reason == RouteReason.OFF_ROUTE) RouteUpdated.OFF_ROUTE else RouteUpdated.INITIAL),
        )
}
