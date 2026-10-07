package veeci.practicing.rts.identity.application

import veeci.practicing.rts.identity.application.port.out.UserRepository
import veeci.practicing.rts.identity.domain.User
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.security.AccessTokens
import veeci.practicing.rts.platform.security.IssuedToken
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import java.util.UUID

data class IssueDevToken(
    /** Null creates a new user. */
    val userId: UserId?,
    val role: Role,
    val displayName: String,
)

data class DevToken(
    val user: User,
    val token: IssuedToken,
)

/**
 * Creates users and hands out tokens without any password, so developers and tests can act as any user.
 * Mounted only in dev and test; production gets its users and tokens from a real identity provider.
 */
class DevTokenService(
    private val users: UserRepository,
    private val transactions: TransactionRunner,
    private val tokens: AccessTokens,
) {
    suspend fun issue(command: IssueDevToken): DevToken =
        transactions.inTransaction {
            val user = User(command.userId ?: UserId(UUID.randomUUID()), command.displayName, command.role)
            users.save(user)
            DevToken(user, tokens.issue(Actor(user.id, user.role)))
        }
}
