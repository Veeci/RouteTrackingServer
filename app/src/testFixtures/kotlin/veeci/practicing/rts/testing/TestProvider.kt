package veeci.practicing.rts.testing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for the identity provider (phase 12g): it publishes one RSA public key as a JWKS on a local port and
 * signs RS256 tokens shaped like Keycloak's, with the roles in `realm_access.roles`. [env] points the server at it.
 * Each instance has its own key pair and port, so tests do not share state. Close it after the test.
 */
class TestProvider : AutoCloseable {
    private val keys: KeyPair = rsaKeyPair()

    /** Keys that sign but are not published: a token signed with them carries a `kid` the JWKS does not list. */
    private val unpublished: KeyPair = rsaKeyPair()

    private val fetches = AtomicInteger()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(JWKS_PATH) { exchange ->
                fetches.incrementAndGet()
                val body = jwks().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    val issuer = "http://127.0.0.1:${server.address.port}/realms/rts"

    /** How often the server fetched the JWKS. */
    val jwksFetches: Int get() = fetches.get()

    /** The server's config for this provider. The JWKS sits at Keycloak's path, so `OIDC_JWKS` stays empty. */
    val env: Map<String, String> get() = mapOf("OIDC_ISSUER" to issuer)

    fun admin(subject: String = newSubject()): String = token(subject = subject, roles = listOf(ADMIN_ROLE))

    fun operator(subject: String = newSubject()): String = token(subject = subject, roles = listOf(OPERATOR_ROLE))

    /** A token as the provider writes it. Every part can be changed to make the token wrong in one way. */
    fun token(
        subject: String = newSubject(),
        roles: List<String> = listOf(OPERATOR_ROLE),
        issuer: String = this.issuer,
        audience: String = AUDIENCE,
        expiresAt: Instant = Instant.now().plusSeconds(TOKEN_TTL_S),
        keyId: String = KEY_ID,
        signedWithUnpublishedKey: Boolean = false,
    ): String {
        val pair = if (signedWithUnpublishedKey) unpublished else keys
        return JWT
            .create()
            .withKeyId(keyId)
            .withIssuer(issuer)
            .withAudience(audience, "account")
            .withSubject(subject)
            .withClaim("realm_access", mapOf("roles" to roles))
            .withClaim("preferred_username", "tester")
            .withIssuedAt(expiresAt.minusSeconds(TOKEN_TTL_S))
            .withExpiresAt(expiresAt)
            .sign(Algorithm.RSA256(pair.public as RSAPublicKey, pair.private as RSAPrivateKey))
    }

    /** An HS256 token that claims this provider's issuer, signed with the server's own test key. */
    fun hs256WithProviderIssuer(): String =
        JWT
            .create()
            .withIssuer(issuer)
            .withAudience(AUDIENCE)
            .withSubject(newSubject())
            .withClaim("realm_access", mapOf("roles" to listOf(ADMIN_ROLE)))
            .withExpiresAt(Instant.now().plusSeconds(TOKEN_TTL_S))
            .sign(Algorithm.HMAC256(TEST_JWT_SECRET))

    override fun close() = server.stop(0)

    private fun jwks(): String {
        val key = keys.public as RSAPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val n =
            encoder.encodeToString(
                key.modulus
                    .toByteArray()
                    .dropWhile { it == 0.toByte() }
                    .toByteArray(),
            )
        val e = encoder.encodeToString(key.publicExponent.toByteArray())
        return """{"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"$KEY_ID","n":"$n","e":"$e"}]}"""
    }

    companion object {
        const val KEY_ID = "test-key-1"
        const val AUDIENCE = "rts-api"
        const val ADMIN_ROLE = "rts-admin"
        const val OPERATOR_ROLE = "rts-operator"
        private const val JWKS_PATH = "/realms/rts/protocol/openid-connect/certs"
        private const val TOKEN_TTL_S = 300L
        private const val KEY_BITS = 2048

        fun newSubject(): String = UUID.randomUUID().toString()

        private fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
    }
}
