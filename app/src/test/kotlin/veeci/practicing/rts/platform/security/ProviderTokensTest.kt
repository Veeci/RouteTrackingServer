package veeci.practicing.rts.platform.security

import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.platform.config.OidcConfig
import veeci.practicing.rts.shared.Role
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** The role mapping and the user ids of [ProviderTokens] (phase 12g). Signatures are tested in `ProviderTokenIT`. */
class ProviderTokensTest {
    private val config = OidcConfig(issuer = ISSUER)

    private fun tokens(config: OidcConfig = this.config) =
        ProviderTokens(config, Clock.systemUTC(), 30.seconds, JwkProvider { error("no keys in this test") })

    /** Claims only: [ProviderTokens.actorOf] reads a payload that a verifier has already checked. */
    private fun payload(build: com.auth0.jwt.JWTCreator.Builder.() -> Unit) =
        JWT.decode(
            JWT
                .create()
                .withIssuer(ISSUER)
                .withSubject("alice")
                .apply(build)
                .sign(Algorithm.none()),
        )

    private fun realmRoles(vararg roles: String) = payload { withClaim("realm_access", mapOf("roles" to roles.toList())) }

    @Test
    fun `the operator role maps to OPERATOR`() {
        tokens().actorOf(realmRoles("rts-operator"))?.role shouldBe Role.OPERATOR
    }

    @Test
    fun `ADMIN wins when the account has both roles`() {
        tokens().actorOf(realmRoles("rts-operator", "rts-admin"))?.role shouldBe Role.ADMIN
    }

    @Test
    fun `no staff role, no roles claim, or a roles claim of the wrong shape gives no actor`() {
        tokens().actorOf(realmRoles("offline_access")) shouldBe null
        tokens().actorOf(payload {}) shouldBe null
        tokens().actorOf(payload { withClaim("realm_access", "rts-admin") }) shouldBe null
        tokens().actorOf(payload { withClaim("realm_access", mapOf("roles" to "rts-admin")) }) shouldBe null
    }

    @Test
    fun `a token without a subject gives no actor`() {
        val noSubject =
            JWT.decode(
                JWT
                    .create()
                    .withIssuer(ISSUER)
                    .withClaim("realm_access", mapOf("roles" to listOf("rts-admin")))
                    .sign(Algorithm.none()),
            )

        tokens().actorOf(noSubject) shouldBe null
    }

    @Test
    fun `a flat roles claim and custom role names are read from the config`() {
        val custom = tokens(config.copy(roleClaim = "roles", adminRole = "boss", operatorRole = "staff"))

        custom.actorOf(payload { withClaim("roles", listOf("staff")) })?.role shouldBe Role.OPERATOR
        custom.actorOf(payload { withClaim("roles", listOf("boss")) })?.role shouldBe Role.ADMIN
    }

    @Test
    fun `the user id is a stable version 5 UUID of the issuer and the subject`() {
        val id = tokens().userIdOf("alice").value

        // Python: uuid.uuid5(uuid.NAMESPACE_URL, "https://sso.test/realms/rts#alice")
        id shouldBe UUID.fromString("cac5469b-20c7-5953-a64d-ef44188c242a")
        id.version() shouldBe 5
        tokens().actorOf(realmRoles("rts-admin"))?.userId?.value shouldBe id
        tokens(config.copy(issuer = "https://other.test/realms/rts")).userIdOf("alice").value shouldNotBe id
    }

    @Test
    fun `the JWKS URL defaults to Keycloak's path and can be set apart from the issuer`() {
        config.jwksUrl shouldBe "$ISSUER/protocol/openid-connect/certs"
        config.copy(jwks = "http://keycloak:8080/certs").jwksUrl shouldBe "http://keycloak:8080/certs"
    }

    private companion object {
        const val ISSUER = "https://sso.test/realms/rts"
    }
}
