package veeci.practicing.rts.testing

import veeci.practicing.rts.platform.security.AccessTokens
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Access tokens for tests, signed with the test profile's key, so a test needs no call to the dev token
 * endpoint. The users do not exist in the `users` table; nothing in phases 2 and 3 reads that table for them.
 */
object TestTokens {
    private val tokens = AccessTokens(TestConfig.load().jwt, Clock.systemUTC())

    fun guest(id: UserId = newUser()): String = of(Actor(id, Role.GUEST))

    fun driver(id: UserId = newUser()): String = of(Actor(id, Role.DRIVER))

    fun admin(id: UserId = newUser()): String = of(Actor(id, Role.ADMIN))

    fun of(actor: Actor): String = tokens.issue(actor).token

    /** A token that expired a day ago (far outside the 30 s leeway). */
    fun expired(actor: Actor = Actor(newUser(), Role.GUEST)): String =
        AccessTokens(TestConfig.load().jwt, Clock.fixed(Instant.now().minusSeconds(DAY_S), ZoneOffset.UTC)).issue(actor).token

    fun newUser(): UserId = UserId(UUID.randomUUID())

    private const val DAY_S = 86_400L
}
