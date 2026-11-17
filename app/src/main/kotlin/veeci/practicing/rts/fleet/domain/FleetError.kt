package veeci.practicing.rts.fleet.domain

import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode

enum class FleetError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** The fleet view is for staff; export and rebuild are for admins. */
    FLEET_FORBIDDEN(ErrorCategory.FORBIDDEN),

    /** No driver with that id, neither in the fleet view nor as an account. */
    DRIVER_NOT_FOUND(ErrorCategory.NOT_FOUND),
}
