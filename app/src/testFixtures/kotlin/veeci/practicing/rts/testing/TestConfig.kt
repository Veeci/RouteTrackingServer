package veeci.practicing.rts.testing

import veeci.practicing.rts.platform.config.AppConfig
import veeci.practicing.rts.platform.config.AppConfigLoader

const val TEST_JWT_SECRET = "test-only-signing-key-0123456789abcdef"

object TestConfig {
    private val defaults =
        mapOf(
            "APP_ENV" to "test",
            "DB_URL" to "jdbc:postgresql://localhost:5432/unused",
            "DB_USER" to "test",
            "DB_PASSWORD" to "test",
            // A fixed key, so tests can mint tokens themselves (TestTokens). Only for tests, never a real secret.
            "JWT_SECRET" to TEST_JWT_SECRET,
        )

    fun load(overrides: Map<String, String> = emptyMap()): AppConfig = AppConfigLoader.load(defaults + overrides)
}
