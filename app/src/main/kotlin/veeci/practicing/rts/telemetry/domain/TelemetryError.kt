package veeci.practicing.rts.telemetry.domain

import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode

enum class TelemetryError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** No such session, or its health is not computed yet. */
    SESSION_HEALTH_NOT_FOUND(ErrorCategory.NOT_FOUND),

    /** Session health is for admins only. */
    SESSION_HEALTH_FORBIDDEN(ErrorCategory.FORBIDDEN),
}
