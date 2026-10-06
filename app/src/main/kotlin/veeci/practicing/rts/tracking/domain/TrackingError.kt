package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode

enum class TrackingError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** A device tried to continue a session that another device started. */
    SESSION_DEVICE_MISMATCH(ErrorCategory.CONFLICT),
}
