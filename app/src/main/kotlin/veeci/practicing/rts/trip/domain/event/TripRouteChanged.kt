package veeci.practicing.rts.trip.domain.event

import kotlinx.serialization.Serializable
import veeci.practicing.rts.trip.domain.TripRoute
import java.util.UUID
import kotlin.time.Instant

/** The trip got a new route the driver follows from now on (phase 7b: after leaving the old one). */
data class TripRouteChanged(
    val route: TripRoute,
    val guestId: UUID,
    val driverId: UUID?,
)

/**
 * [TripRouteChanged] as JSON in the outbox, under the event type [TYPE]. A contract with every reader: fields may
 * be added, but a change to an existing field needs a new type, `...v2`. It carries the whole route, so a reader
 * (the live context) can send it on without reading the trip context's tables.
 */
@Serializable
data class TripRouteChangedV1(
    val tripId: String,
    val guestId: String,
    val driverId: String? = null,
    /** TRIP or APPROACH. */
    val kind: String,
    val version: Int,
    /** INITIAL or OFF_ROUTE; a newer writer may add values. */
    val reason: String,
    /** Google Encoded Polyline, precision 5. */
    val polyline: String,
    val distanceM: Double,
    val durationS: Double,
    val at: Instant,
) {
    companion object {
        const val TYPE = "trip.TripRouteChanged.v1"

        fun of(change: TripRouteChanged) =
            with(change.route) {
                TripRouteChangedV1(
                    tripId.value.toString(),
                    change.guestId.toString(),
                    change.driverId?.toString(),
                    kind.name,
                    version,
                    reason.name,
                    polyline,
                    distanceM,
                    durationS,
                    createdAt,
                )
            }
    }
}
