package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode

enum class TrackingError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** A device tried to continue a session that another device started. */
    SESSION_DEVICE_MISMATCH(ErrorCategory.CONFLICT),

    /** A batch arrived for a session that was never opened with `hello`. */
    SESSION_NOT_FOUND(ErrorCategory.NOT_FOUND),

    /** A batch without fixes. */
    EMPTY_BATCH(ErrorCategory.INVALID),

    /** A batch with more fixes than the server accepts in one batch. */
    BATCH_TOO_LARGE(ErrorCategory.INVALID),

    /** A seq below 1. */
    INVALID_SEQ(ErrorCategory.INVALID),
}
