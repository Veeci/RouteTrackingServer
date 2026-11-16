package veeci.practicing.rts.trip.domain.event

import kotlinx.serialization.Serializable
import veeci.practicing.rts.trip.domain.TripRoute
import java.util.UUID
import kotlin.time.Instant

/** Why clients get a route now. */
enum class RouteChangeReason {
    /** The first plan of its kind: the APPROACH route from the driver's first fix after accept. */
    INITIAL,

    /** The ride started, so the TRIP route takes over from the APPROACH route. */
    STATUS_CHANGED,

    /** The driver left the previous version. */
    OFF_ROUTE,
}

/** The driver follows [route] from now on (phase 7). */
data class TripRouteChanged(
    val route: TripRoute,
    val guestId: UUID,
    val driverId: UUID?,
    val reason: RouteChangeReason,
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
    /** A [RouteChangeReason] name; a newer writer may add values. */
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
                    change.reason.name,
                    polyline,
                    distanceM,
                    durationS,
                    createdAt,
                )
            }
    }
}
