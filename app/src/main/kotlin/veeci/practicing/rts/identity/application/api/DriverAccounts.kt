package veeci.practicing.rts.identity.application.api

import veeci.practicing.rts.shared.UserId

/** The identity context's public API for the driver socket (phase 11). Joins the caller's transaction if any. */
interface DriverAccounts {
    /** True when an admin disabled [driver]. A user with no account row counts as enabled. */
    suspend fun isDisabled(driver: UserId): Boolean
}
