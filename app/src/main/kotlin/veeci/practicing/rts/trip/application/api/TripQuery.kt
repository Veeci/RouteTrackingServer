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
