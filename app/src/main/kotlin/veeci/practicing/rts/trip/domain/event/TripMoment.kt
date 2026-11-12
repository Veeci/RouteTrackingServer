package veeci.practicing.rts.trip.domain.event

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Instant

/**
 * Something on a trip worth telling the guest that is not a change of stage (those are [TripStatusChanged]).
 * Moments are stored for the trip's history and reach guests through the outbox, so a guest never sees a moment
 * that was rolled back.
 */
sealed interface TripMoment {
    val tripId: UUID
    val guestId: UUID

    /** The trip's stage when the moment happened. */
    val status: TripStatus
    val at: Instant

    /** The driver came within the arriving distance of the pickup. [remainingM] is the straight-line distance. */
    data class DriverArriving(
        override val tripId: UUID,
        override val guestId: UUID,
        override val status: TripStatus,
        override val at: Instant,
        val remainingM: Double,
    ) : TripMoment
}

/**
 * [TripMoment] as JSON in the outbox and in `trip_moments`, under the event type [TYPE]. A contract with every
 * reader: fields may be added, but a change to an existing field needs a new type, `...v2`.
 */
@Serializable
data class TripMomentRaisedV1(
    val tripId: String,
    val guestId: String,
    /** One of the KIND_ constants. A newer writer may add kinds; [toMoment] returns null for those. */
    val kind: String,
    val status: String,
    val at: Instant,
    val remainingM: Double? = null,
) {
    fun toMoment(): TripMoment? =
        when (kind) {
            KIND_DRIVER_ARRIVING -> {
                TripMoment.DriverArriving(
                    UUID.fromString(tripId),
                    UUID.fromString(guestId),
                    TripStatus.valueOf(status),
                    at,
                    checkNotNull(remainingM) { "$KIND_DRIVER_ARRIVING needs remainingM" },
                )
            }

            else -> {
                null
            }
        }

    companion object {
        const val TYPE = "trip.TripMomentRaised.v1"
        const val KIND_DRIVER_ARRIVING = "DRIVER_ARRIVING"

        fun of(moment: TripMoment) =
            when (moment) {
                is TripMoment.DriverArriving -> {
                    TripMomentRaisedV1(
                        moment.tripId.toString(),
                        moment.guestId.toString(),
                        KIND_DRIVER_ARRIVING,
                        moment.status.name,
                        moment.at,
                        moment.remainingM,
                    )
                }
            }
    }
}
