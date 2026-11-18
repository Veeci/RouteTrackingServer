package veeci.practicing.rts.platform.config

import com.sksamuel.hoplite.ConfigException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class AppConfigLoaderTest {
    private val platformEnv =
        mapOf(
            "DB_URL" to "jdbc:postgresql://db.internal:5432/rts",
            "DB_USER" to "rts_app",
            "DB_PASSWORD" to "s3cret-value",
            "JWT_SECRET" to "a-signing-key-of-at-least-32-characters",
            "STAGING_ACCESS_TOKEN" to "a-staging-token-of-at-least-32-characters",
        )

    @ParameterizedTest
    @EnumSource(AppEnv::class)
    fun `TC-1-CFG-01 every profile decodes to a complete AppConfig`(env: AppEnv) {
        val config = AppConfigLoader.load(platformEnv + ("APP_ENV" to env.name.lowercase()))
        config.app.env shouldBe env
        config.db.password.value shouldBe "s3cret-value"
    }

    @Test
    fun `TC-1-CFG-02 missing password and invalid port are reported together`() {
        val error =
            shouldThrow<ConfigException> {
                AppConfigLoader.load(mapOf("APP_ENV" to "dev", "HTTP_PORT" to "eighty"))
            }

        error.message shouldContain "password"
        error.message shouldContain "port"
    }

    @Test
    fun `TC-1-CFG-03 an environment variable overrides the file value`() {
        val config = AppConfigLoader.load(platformEnv + ("APP_ENV" to "dev"))

        config.db.url shouldBe "jdbc:postgresql://db.internal:5432/rts" // dev.conf says localhost
    }

    @Test
    fun `profile values override base values`() {
        val dev = AppConfigLoader.load(platformEnv + ("APP_ENV" to "dev"))
        val prod = AppConfigLoader.load(platformEnv + ("APP_ENV" to "prod"))

        dev.db.migrateOnStart shouldBe true
        prod.db.migrateOnStart shouldBe false
    }

    @Test
    fun `missing or unknown APP_ENV refuses to load`() {
        shouldThrow<ConfigException> { AppConfigLoader.load(platformEnv) }
            .message shouldContain "APP_ENV is not set"
        shouldThrow<ConfigException> { AppConfigLoader.load(platformEnv + ("APP_ENV" to "production")) }
            .message shouldContain "unknown"
    }

    @Test
    fun `ADMIN_ORIGINS is a comma separated list, and empty by default`() {
        val origins = "https://admin.example.com,http://localhost:5173"
        val config = AppConfigLoader.load(platformEnv + mapOf("APP_ENV" to "prod", "ADMIN_ORIGINS" to origins))

        config.admin.origins shouldBe listOf("https://admin.example.com", "http://localhost:5173")
        AppConfigLoader.load(platformEnv + ("APP_ENV" to "prod")).admin.origins shouldBe emptyList()
    }

    @Test
    fun `TC-1b-CFG-01 the staging profile decodes with the staging settings`() {
        val config = AppConfigLoader.load(platformEnv + ("APP_ENV" to "staging"))

        config.app.env shouldBe AppEnv.STAGING
        config.db.migrateOnStart shouldBe false
        config.staging.access.token
            ?.value shouldBe "a-staging-token-of-at-least-32-characters"
        config.http.trustForwardedHeaders shouldBe false
        config.redis.enabled shouldBe false
    }

    @Test
    fun `TC-1b-CFG-01 staging refuses to start without a staging access token of 32 characters or more`() {
        shouldThrow<ConfigException> { AppConfigLoader.load(platformEnv - "STAGING_ACCESS_TOKEN" + ("APP_ENV" to "staging")) }
            .message shouldContain "STAGING_ACCESS_TOKEN"
        shouldThrow<ConfigException> {
            AppConfigLoader.load(platformEnv + mapOf("APP_ENV" to "staging", "STAGING_ACCESS_TOKEN" to "x".repeat(31)))
        }.message shouldContain "32"
        AppConfigLoader.load(platformEnv + mapOf("APP_ENV" to "staging", "STAGING_ACCESS_TOKEN" to "x".repeat(32))).app.env shouldBe
            AppEnv.STAGING
    }

    @Test
    fun `other profiles start without a staging access token`() {
        val config = AppConfigLoader.load(platformEnv - "STAGING_ACCESS_TOKEN" + ("APP_ENV" to "prod"))

        config.staging.access.token shouldBe null
    }

    @Test
    fun `HTTP_TRUSTFORWARDEDHEADERS turns on the forwarded headers`() {
        AppConfigLoader
            .load(platformEnv + mapOf("APP_ENV" to "staging", "HTTP_TRUSTFORWARDEDHEADERS" to "true"))
            .http.trustForwardedHeaders shouldBe true
    }

    @Test
    fun `the password never appears when the config is printed`() {
        val config = AppConfigLoader.load(platformEnv + ("APP_ENV" to "prod"))

        config.toString() shouldNotContain "s3cret-value"
        config.toString() shouldNotContain "a-staging-token"
    }
}
