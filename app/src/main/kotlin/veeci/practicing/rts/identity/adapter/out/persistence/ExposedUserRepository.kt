package veeci.practicing.rts.identity.adapter.out.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import veeci.practicing.rts.identity.application.port.out.UserRepository
import veeci.practicing.rts.identity.domain.User
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId

/** The `users` table (migration V4). */
internal object UsersTable : Table("users") {
    val id = javaUUID("id")
    val displayName = text("display_name")
    val role = text("role")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** Runs inside the caller's transaction (see TransactionRunner); it opens none of its own. */
class ExposedUserRepository : UserRepository {
    override suspend fun find(id: UserId): User? =
        UsersTable
            .selectAll()
            .where { UsersTable.id eq id.value }
            .singleOrNull()
            ?.let { User(UserId(it[UsersTable.id]), it[UsersTable.displayName], Role.valueOf(it[UsersTable.role])) }

    override suspend fun save(user: User) {
        UsersTable.upsert(onUpdateExclude = listOf(UsersTable.createdAt)) {
            it[id] = user.id.value
            it[displayName] = user.displayName
            it[role] = user.role.name
        }
    }
}
