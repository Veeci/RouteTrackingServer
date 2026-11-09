package veeci.practicing.rts.trip.domain.event

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Instant

/** A trip moved to another stage. [from] is null when the trip was just requested. */
data class TripStatusChanged(
    val tripId: UUID,
    val guestId: UUID,
    val driverId: UUID?,
    val from: TripStatus?,
    val to: TripStatus,
    val at: Instant,
)

/**
 * [TripStatusChanged] as JSON in the outbox, under the event type [TYPE]. A contract with every reader:
 * fields may be added, but a change to an existing field needs a new type, `...v2`.
 */
@Serializable
data class TripStatusChangedV1(
    val tripId: String,
    val guestId: String,
    val driverId: String? = null,
    val from: String? = null,
    val to: String,
    val at: Instant,
) {
    fun toEvent() =
        TripStatusChanged(
            tripId = UUID.fromString(tripId),
            guestId = UUID.fromString(guestId),
            driverId = driverId?.let(UUID::fromString),
            from = from?.let(TripStatus::valueOf),
            to = TripStatus.valueOf(to),
            at = at,
        )

    companion object {
        const val TYPE = "trip.TripStatusChanged.v1"

        fun of(event: TripStatusChanged) =
            TripStatusChangedV1(
                tripId = event.tripId.toString(),
                guestId = event.guestId.toString(),
                driverId = event.driverId?.toString(),
                from = event.from?.name,
                to = event.to.name,
                at = event.at,
            )
    }
}
