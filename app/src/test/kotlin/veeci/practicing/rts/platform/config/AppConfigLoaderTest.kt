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
    fun `the password never appears when the config is printed`() {
        val config = AppConfigLoader.load(platformEnv + ("APP_ENV" to "prod"))

        config.toString() shouldNotContain "s3cret-value"
    }
}
