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

    /** The driver's phone battery is low: [levelPercent] or a CRITICAL profile. */
    data class DriverLowBattery(
        override val tripId: UUID,
        override val guestId: UUID,
        override val status: TripStatus,
        override val at: Instant,
        val levelPercent: Int,
    ) : TripMoment

    /** Nothing came from the driver's phone for the stale time. [lastSeenAt] is when it was last heard. */
    data class DriverStale(
        override val tripId: UUID,
        override val guestId: UUID,
        override val status: TripStatus,
        override val at: Instant,
        val lastSeenAt: Instant,
    ) : TripMoment

    /** The driver's phone is heard again after a [DriverStale]. */
    data class DriverBack(
        override val tripId: UUID,
        override val guestId: UUID,
        override val status: TripStatus,
        override val at: Instant,
    ) : TripMoment

    /**
     * The driver left the planned route and the server planned again from their position (phase 7b). A new route
     * follows as a separate event when the plan succeeded; when it failed the old route stays.
     */
    data class OffRoute(
        override val tripId: UUID,
        override val guestId: UUID,
        override val status: TripStatus,
        override val at: Instant,
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
    val levelPercent: Int? = null,
    val lastSeenAt: Instant? = null,
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

            KIND_DRIVER_LOW_BATTERY -> {
                TripMoment.DriverLowBattery(
                    UUID.fromString(tripId),
                    UUID.fromString(guestId),
                    TripStatus.valueOf(status),
                    at,
                    checkNotNull(levelPercent) { "$KIND_DRIVER_LOW_BATTERY needs levelPercent" },
                )
            }

            KIND_DRIVER_STALE -> {
                TripMoment.DriverStale(
                    UUID.fromString(tripId),
                    UUID.fromString(guestId),
                    TripStatus.valueOf(status),
                    at,
                    checkNotNull(lastSeenAt) { "$KIND_DRIVER_STALE needs lastSeenAt" },
                )
            }

            KIND_DRIVER_BACK -> {
                TripMoment.DriverBack(UUID.fromString(tripId), UUID.fromString(guestId), TripStatus.valueOf(status), at)
            }

            KIND_OFF_ROUTE -> {
                TripMoment.OffRoute(UUID.fromString(tripId), UUID.fromString(guestId), TripStatus.valueOf(status), at)
            }

            else -> {
                null
            }
        }

    companion object {
        const val TYPE = "trip.TripMomentRaised.v1"
        const val KIND_DRIVER_ARRIVING = "DRIVER_ARRIVING"
        const val KIND_DRIVER_LOW_BATTERY = "DRIVER_LOW_BATTERY"
        const val KIND_DRIVER_STALE = "DRIVER_STALE"
        const val KIND_DRIVER_BACK = "DRIVER_BACK"
        const val KIND_OFF_ROUTE = "OFF_ROUTE"

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

                is TripMoment.DriverLowBattery -> {
                    TripMomentRaisedV1(
                        moment.tripId.toString(),
                        moment.guestId.toString(),
                        KIND_DRIVER_LOW_BATTERY,
                        moment.status.name,
                        moment.at,
                        levelPercent = moment.levelPercent,
                    )
                }

                is TripMoment.DriverStale -> {
                    TripMomentRaisedV1(
                        moment.tripId.toString(),
                        moment.guestId.toString(),
                        KIND_DRIVER_STALE,
                        moment.status.name,
                        moment.at,
                        lastSeenAt = moment.lastSeenAt,
                    )
                }

                is TripMoment.DriverBack -> {
                    TripMomentRaisedV1(moment.tripId.toString(), moment.guestId.toString(), KIND_DRIVER_BACK, moment.status.name, moment.at)
                }

                is TripMoment.OffRoute -> {
                    TripMomentRaisedV1(moment.tripId.toString(), moment.guestId.toString(), KIND_OFF_ROUTE, moment.status.name, moment.at)
                }
            }
    }
}
