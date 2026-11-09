package veeci.practicing.rts.identity.application.port.out

import veeci.practicing.rts.identity.domain.User
import veeci.practicing.rts.shared.UserId

interface UserRepository {
    suspend fun find(id: UserId): User?

    /** Inserts the user, or replaces the stored name and role. */
    suspend fun save(user: User)
}
