package veeci.practicing.rts.platform.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Payload
import veeci.practicing.rts.platform.config.JwtConfig
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import java.time.Clock
import java.util.UUID
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinInstant

/** A signed access token and the moment it stops being valid. */
data class IssuedToken(
    val token: String,
    val expiresAt: Instant,
)

/**
 * Issues and checks access tokens (JWT, HMAC-SHA256). A token says who the caller is (`sub` = user id) and
 * which role they have (`role`). The server checks the signature, `iss`, `aud` and `exp`, and needs no
 * database lookup for that. A later identity provider (for example Firebase Auth) would replace [issue]
 * and the key, not the routes.
 */
class AccessTokens(
    private val config: JwtConfig,
    private val clock: Clock,
) {
    init {
        require(config.secret.value.length >= MIN_SECRET_LENGTH) {
            "JWT_SECRET must have at least $MIN_SECRET_LENGTH characters"
        }
    }

    private val algorithm = Algorithm.HMAC256(config.secret.value)

    /** Checks signature, issuer, audience and expiry, with [JwtConfig.leeway] for clock differences. */
    val verifier: JWTVerifier =
        (
            JWT
                .require(algorithm)
                .withIssuer(config.issuer)
                .withAudience(config.audience)
                .acceptLeeway(config.leeway.inWholeSeconds) as JWTVerifier.BaseVerification
        ).build(clock)

    fun issue(actor: Actor): IssuedToken {
        val now = clock.instant()
        val expiresAt = now.plus(config.tokenTtl.toJavaDuration())
        val token =
            JWT
                .create()
                .withIssuer(config.issuer)
                .withAudience(config.audience)
                .withSubject(actor.userId.value.toString())
                .withClaim(ROLE_CLAIM, actor.role.name)
                .withIssuedAt(now)
                .withExpiresAt(expiresAt)
                .sign(algorithm)
        return IssuedToken(token, expiresAt.toKotlinInstant())
    }

    /** The caller a verified token speaks for, or null if its claims are not ones this server issues. */
    fun actorOf(token: Payload): Actor? {
        val userId = token.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val role = token.getClaim(ROLE_CLAIM).asString()?.let { name -> Role.entries.firstOrNull { it.name == name } }
        return if (userId != null && role != null) Actor(UserId(userId), role) else null
    }

    private companion object {
        const val ROLE_CLAIM = "role"
        const val MIN_SECRET_LENGTH = 32
    }
}
