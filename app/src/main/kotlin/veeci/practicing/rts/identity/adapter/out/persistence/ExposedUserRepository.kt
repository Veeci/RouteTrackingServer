package veeci.practicing.rts.identity.adapter.out.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import veeci.practicing.rts.identity.application.port.out.UserRepository
import veeci.practicing.rts.identity.domain.Disabled
import veeci.practicing.rts.identity.domain.User
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId

/** The `users` table (migration V4). */
internal object UsersTable : Table("users") {
    val id = javaUUID("id")
    val displayName = text("display_name")
    val role = text("role")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val disabledAt = timestamp("disabled_at").nullable() // migration V19
    val disabledReason = text("disabled_reason").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Runs inside the caller's transaction (see TransactionRunner); it opens none of its own. */
class ExposedUserRepository : UserRepository {
    override suspend fun find(id: UserId): User? =
        UsersTable
            .selectAll()
            .where { UsersTable.id eq id.value }
            .singleOrNull()
            ?.toUser()

    override suspend fun findByRole(role: Role): List<User> =
        UsersTable
            .selectAll()
            .where { UsersTable.role eq role.name }
            .map { it.toUser() }

    private fun ResultRow.toUser() =
        User(
            UserId(this[UsersTable.id]),
            this[UsersTable.displayName],
            Role.valueOf(this[UsersTable.role]),
            this[UsersTable.disabledAt]?.let { Disabled(it, this[UsersTable.disabledReason].orEmpty()) },
        )

    override suspend fun save(user: User) {
        UsersTable.upsert(onUpdateExclude = listOf(UsersTable.createdAt, UsersTable.disabledAt, UsersTable.disabledReason)) {
            it[id] = user.id.value
            it[displayName] = user.displayName
            it[role] = user.role.name
        }
    }

    override suspend fun saveStatus(user: User) {
        UsersTable.update({ UsersTable.id eq user.id.value }) {
            it[disabledAt] = user.disabled?.at
            it[disabledReason] = user.disabled?.reason
        }
    }
}
