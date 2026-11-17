package veeci.practicing.rts.trip.domain

import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode

enum class TripError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** No such trip, or one the caller may not see. The two look the same, so trip ids cannot be probed. */
    TRIP_NOT_FOUND(ErrorCategory.NOT_FOUND),

    /** The action is not possible in the trip's current stage, e.g. starting a trip that is only REQUESTED. */
    ILLEGAL_TRANSITION(ErrorCategory.CONFLICT),

    /** The caller sees the trip but may not do this to it, e.g. a guest accepting it. */
    TRIP_ACTION_FORBIDDEN(ErrorCategory.FORBIDDEN),

    /** Someone else changed the trip between reading and saving it. Read it again and retry. */
    CONCURRENT_MODIFICATION(ErrorCategory.CONFLICT),

    /** The driver already has an active trip; a driver drives one trip at a time. */
    DRIVER_ALREADY_ON_TRIP(ErrorCategory.CONFLICT),

    /** The caller's copy of the trip (If-Match) is older than the stored one. */
    VERSION_MISMATCH(ErrorCategory.STALE),

    /** The trip has no summary yet: it is not completed, or the summary is still being computed. */
    SUMMARY_NOT_READY(ErrorCategory.NOT_FOUND),

    /** The request needs a trip that has ended, and this one is still running. */
    TRIP_NOT_ENDED(ErrorCategory.CONFLICT),

    /** The trip ended longer ago than the retention period: its GPS points are deleted (phase 10). */
    TRACK_EXPIRED(ErrorCategory.GONE),
}
