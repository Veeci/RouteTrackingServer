package veeci.practicing.rts.testing.trip

import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripAction
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Object mother for trips: actors, places and trips already moved to a given stage. */
object TripSamples {
    /** Milliseconds on purpose: stored times must keep them. */
    val T0: Instant = Instant.parse("2026-10-07T09:00:00.123Z")

    /** Ben Thanh market and Saigon central post office, about 1.3 km apart. */
    val PICKUP = GeoPoint(10.7725, 106.6980)
    val DESTINATION = GeoPoint(10.7798, 106.6990)

    fun guest(): Actor = Actor(UserId(UUID.randomUUID()), Role.GUEST)

    fun driver(): Actor = Actor(UserId(UUID.randomUUID()), Role.DRIVER)

    fun admin(): Actor = Actor(UserId(UUID.randomUUID()), Role.ADMIN)

    fun requested(
        guest: Actor = guest(),
        at: Instant = T0,
        id: TripId = TripId(UUID.randomUUID()),
    ): Trip = Trip.request(guest, PICKUP, DESTINATION, id, at).also { it.pullEvents() }

    /**
     * A trip moved to [status] by its own actors, with events already pulled. [driver] accepts it when the
     * status needs a driver; a trip cancelled by these samples is cancelled by its guest.
     */
    fun inStatus(
        status: TripStatus,
        guest: Actor = guest(),
        driver: Actor = driver(),
        at: Instant = T0,
    ): Trip {
        val trip = requested(guest, at)
        val path =
            when (status) {
                TripStatus.REQUESTED -> emptyList()
                TripStatus.ACCEPTED -> listOf(TripAction.ACCEPT)
                TripStatus.ARRIVED -> listOf(TripAction.ACCEPT, TripAction.ARRIVE)
                TripStatus.IN_PROGRESS -> listOf(TripAction.ACCEPT, TripAction.ARRIVE, TripAction.START)
                TripStatus.COMPLETED -> listOf(TripAction.ACCEPT, TripAction.ARRIVE, TripAction.START, TripAction.COMPLETE)
                TripStatus.CANCELLED -> listOf(TripAction.CANCEL)
            }
        path.forEachIndexed { i, action ->
            trip.perform(action, if (action == TripAction.CANCEL) guest else driver, at + (i + 1).minutes)
        }
        trip.pullEvents()
        return trip
    }
}
