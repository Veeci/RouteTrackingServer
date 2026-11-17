package veeci.practicing.rts.identity.application

import veeci.practicing.rts.identity.application.api.DriverAccount
import veeci.practicing.rts.identity.application.api.DriverAccounts
import veeci.practicing.rts.identity.application.port.out.IdentityEventOutbox
import veeci.practicing.rts.identity.application.port.out.UserRepository
import veeci.practicing.rts.identity.domain.Disabled
import veeci.practicing.rts.identity.domain.User
import veeci.practicing.rts.identity.domain.event.DriverAccountChanged
import veeci.practicing.rts.platform.audit.AuditEntry
import veeci.practicing.rts.platform.audit.AuditLog
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.DriverLink
import java.time.Clock
import kotlin.time.toKotlinInstant

enum class IdentityError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** No such driver account. */
    DRIVER_NOT_FOUND(ErrorCategory.NOT_FOUND),

    /** Only an admin may change a driver's account. */
    ACCOUNT_ACTION_FORBIDDEN(ErrorCategory.FORBIDDEN),

    /** Disabling needs a reason, for the audit log and for the driver. */
    REASON_REQUIRED(ErrorCategory.INVALID),
}

/**
 * Disables and enables driver accounts (phase 11, ADMIN). The change and its audit row commit together. After the
 * commit, a disable closes the driver's open sockets on every instance (4403); the next `hello` is refused too.
 */
class DriverAdminService(
    private val users: UserRepository,
    private val audit: AuditLog,
    private val link: DriverLink,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val events: IdentityEventOutbox,
) : DriverAccounts {
    suspend fun disable(
        driver: UserId,
        reason: String,
        actor: Actor,
    ): User {
        if (reason.isBlank()) throw DomainException(IdentityError.REASON_REQUIRED, "Say why the driver is disabled")
        val user =
            change(driver, actor, "DRIVER_DISABLED", reason) {
                it.copy(disabled = Disabled(clock.instant().toKotlinInstant(), reason))
            }
        link.disconnect(driver)
        return user
    }

    suspend fun enable(
        driver: UserId,
        reason: String?,
        actor: Actor,
    ): User = change(driver, actor, "DRIVER_ENABLED", reason) { it.copy(disabled = null) }

    override suspend fun isDisabled(driver: UserId): Boolean = transactions.inTransaction { users.find(driver) }?.disabled != null

    override suspend fun driver(driver: UserId): DriverAccount? =
        transactions.inTransaction { users.find(driver) }?.takeIf { it.role == Role.DRIVER }?.toAccount()

    override suspend fun drivers(): List<DriverAccount> =
        transactions.inTransaction { users.findByRole(Role.DRIVER) }.map { it.toAccount() }

    private fun User.toAccount() = DriverAccount(id.value, displayName, disabled == null, disabled?.at, disabled?.reason)

    private suspend fun change(
        driver: UserId,
        actor: Actor,
        action: String,
        reason: String?,
        update: (User) -> User,
    ): User {
        if (actor.role != Role.ADMIN) throw DomainException(IdentityError.ACCOUNT_ACTION_FORBIDDEN, "Only an admin may change a driver")
        return transactions.inTransaction {
            val user =
                users.find(driver)?.takeIf { it.role == Role.DRIVER }
                    ?: throw DomainException(IdentityError.DRIVER_NOT_FOUND, "No driver ${driver.value}")
            update(user).also {
                users.saveStatus(it)
                audit.record(AuditEntry(actor, action, "DRIVER", driver.value.toString(), reason))
                events.add(DriverAccountChanged(driver.value, it.displayName, it.disabled == null, clock.instant().toKotlinInstant()))
            }
        }
    }
}
