package veeci.practicing.rts.platform.security

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Payload
import com.auth0.jwt.interfaces.RSAKeyProvider
import veeci.practicing.rts.platform.config.OidcConfig
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Checks the RS256 access tokens of the admin dashboard's identity provider (phase 12g), next to the server's own
 * HS256 tokens in [AccessTokens]. The provider signs with a private key and publishes the public keys as a JWKS; the
 * token's `kid` header says which key signed it. The server never calls the provider per request: keys are cached,
 * and an unknown `kid` (the provider rotated its keys) costs one fetch, rate limited so that forged `kid` values
 * cannot make the server hammer the provider.
 *
 * Only staff log in through the provider. A valid token without a staff role has proven who the caller is, but not
 * that they may use the API: [actorOf] returns null and the caller gets 403, not 401 (see `configureAuthentication`).
 */
class ProviderTokens(
    private val config: OidcConfig,
    clock: Clock,
    leeway: Duration,
    keys: JwkProvider = defaultKeys(config),
) {
    init {
        require(config.enabled) { "ProviderTokens needs OIDC_ISSUER" }
    }

    val issuer: String = config.issuer

    /** Checks the RS256 signature with the key named by `kid`, then `iss`, `aud` and `exp` with [leeway]. */
    val verifier: JWTVerifier =
        (
            JWT
                .require(Algorithm.RSA256(JwksKeys(keys)))
                .withIssuer(config.issuer)
                .withAudience(config.audience)
                .acceptLeeway(leeway.inWholeSeconds) as JWTVerifier.BaseVerification
        ).build(clock)

    /** The caller a verified token speaks for, or null when it has no staff role (or no subject). */
    fun actorOf(token: Payload): Actor? {
        val subject = token.subject?.takeIf { it.isNotBlank() }
        val roles = rolesIn(token)
        val role =
            when {
                config.adminRole in roles -> Role.ADMIN
                config.operatorRole in roles -> Role.OPERATOR
                else -> null
            }
        return if (subject != null && role != null) Actor(userIdOf(subject), role) else null
    }

    /**
     * The server's user id for the provider's `sub`. A provider's subject need not be a UUID (Auth0 writes
     * `auth0|abc`), so the id is a name-based UUID (version 5) of the issuer and the subject: the same person gets the
     * same id on every login, with no table to look it up, and two providers cannot collide.
     */
    fun userIdOf(subject: String): UserId = UserId(uuidV5("$issuer#$subject"))

    private fun rolesIn(token: Payload): Set<String> {
        val path = config.roleClaim.split('.')
        val claim = token.getClaim(path.first())
        var node: Any? =
            runCatching { if (path.size == 1) claim.asList(Any::class.java) else claim.asMap() }.getOrNull()
        for (key in path.drop(1)) node = (node as? Map<*, *>)?.get(key)
        return (node as? List<*>).orEmpty().filterIsInstance<String>().toSet()
    }

    /** Hands java-jwt the public key for a `kid`. A key it cannot get becomes a failed signature check: 401. */
    private class JwksKeys(
        private val keys: JwkProvider,
    ) : RSAKeyProvider {
        override fun getPublicKeyById(keyId: String?): RSAPublicKey? =
            keyId?.let { runCatching { keys.get(it).publicKey as? RSAPublicKey }.getOrNull() }

        override fun getPrivateKey(): RSAPrivateKey? = null

        override fun getPrivateKeyId(): String? = null
    }

    companion object {
        private const val CACHED_KEYS = 10L
        private const val CACHE_HOURS = 24L
        private const val FETCHES_PER_MINUTE = 10L
        private const val TIMEOUT_MS = 2_000

        /** RFC 4122's namespace for URLs; the name hashed under it is the issuer URL plus the subject. */
        private val URL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

        fun defaultKeys(config: OidcConfig): JwkProvider =
            JwkProviderBuilder(URI(config.jwksUrl).toURL())
                .cached(CACHED_KEYS, CACHE_HOURS, TimeUnit.HOURS)
                .rateLimited(FETCHES_PER_MINUTE, 1, TimeUnit.MINUTES)
                .timeouts(TIMEOUT_MS, TIMEOUT_MS)
                .build()

        // The version sits in bits 12 to 15 of the high half, the variant in the two top bits of the low half.
        private const val VERSION_BITS = 0xF000L
        private const val VERSION_5 = 0x5000L
        private const val VARIANT_BITS = -0x4000000000000000L // the two top bits: 0xC000...
        private const val VARIANT_RFC_4122 = Long.MIN_VALUE // the top bits 10: 0x8000...

        /** A version 5 UUID (SHA-1, RFC 4122 section 4.3). The JDK only makes version 3 (MD5). */
        internal fun uuidV5(name: String): UUID {
            val namespace =
                ByteBuffer
                    .allocate(Long.SIZE_BYTES * 2)
                    .putLong(URL_NAMESPACE.mostSignificantBits)
                    .putLong(URL_NAMESPACE.leastSignificantBits)
                    .array()
            // SHA-1 gives 20 bytes; a UUID takes the first 16.
            val hash = ByteBuffer.wrap(MessageDigest.getInstance("SHA-1").digest(namespace + name.toByteArray(Charsets.UTF_8)))
            val high = (hash.long and VERSION_BITS.inv()) or VERSION_5
            val low = (hash.long and VARIANT_BITS.inv()) or VARIANT_RFC_4122
            return UUID(high, low)
        }
    }
}
