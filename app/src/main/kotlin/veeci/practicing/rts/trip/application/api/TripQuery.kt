package veeci.practicing.rts.trip.application.api

import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Instant

/** A trip as other contexts and the web layer see it: plain values, no behaviour. */
data class TripSnapshot(
    val id: UUID,
    val guestId: UUID,
    val driverId: UUID?,
    val pickup: GeoPoint,
    val destination: GeoPoint,
    val status: TripStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Increases by 1 with every change; the web layer sends it as the ETag. */
    val version: Long,
    /** The pickup-to-destination route. Filled where it is cheap (booking, reading one trip); null in lists. */
    val route: RouteSummary? = null,
    /**
     * The route the driver follows now, the one `trip_progress` measures on: the newest APPROACH route before the
     * ride, the newest TRIP route during it. Null when there is none. Filled where [route] is.
     */
    val currentRoute: RouteSummary? = null,
)

/** A stored route in plain values. */
data class RouteSummary(
    /** TRIP (pickup to destination) or APPROACH (driver to pickup). */
    val kind: String,
    /** 1 for the first plan of this kind; each re-plan adds 1. */
    val version: Int,
    /** Google Encoded Polyline, precision 5. */
    val polyline: String,
    val distanceM: Double,
    val durationS: Double,
)

/** The trip context's public read API, for other contexts (the live context checks access with it). */
interface TripQuery {
    /**
     * The trip as [actor] may see it. A trip that does not exist and a trip the actor may not see both throw
     * DomainException with code TRIP_NOT_FOUND.
     */
    suspend fun snapshotFor(
        tripId: UUID,
        actor: Actor,
    ): TripSnapshot
}
