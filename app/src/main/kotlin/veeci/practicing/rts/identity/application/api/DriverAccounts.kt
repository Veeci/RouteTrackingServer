package veeci.practicing.rts.identity.application.api

import veeci.practicing.rts.shared.UserId
import java.util.UUID
import kotlin.time.Instant

/**
 * The identity context's public API for driver accounts: the driver socket asks [isDisabled], the fleet context
 * reads the accounts (phase 11). Joins the caller's transaction if any.
 */
interface DriverAccounts {
    /** True when an admin disabled [driver]. A user with no account row counts as enabled. */
    suspend fun isDisabled(driver: UserId): Boolean

    /** The account of [driver]; null when there is no DRIVER account with that id. */
    suspend fun driver(driver: UserId): DriverAccount?

    /** Every DRIVER account. */
    suspend fun drivers(): List<DriverAccount>
}

/** A driver account in plain values. */
data class DriverAccount(
    val id: UUID,
    val displayName: String,
    val enabled: Boolean,
    val disabledAt: Instant?,
    val disabledReason: String?,
)
