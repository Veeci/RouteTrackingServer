package veeci.practicing.rts.trip.domain.event

/** The stages of a trip. Part of the trip context's public language: events and [TripStatusChanged] carry it. */
enum class TripStatus {
    REQUESTED,
    ACCEPTED,
    ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    ;

    /** A driver is assigned and on the way or driving: the trip receives live positions. */
    val isActive: Boolean get() = this == ACCEPTED || this == ARRIVED || this == IN_PROGRESS

    /** Nothing happens to the trip any more. */
    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED
}
