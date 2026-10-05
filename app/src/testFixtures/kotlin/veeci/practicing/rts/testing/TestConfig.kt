package veeci.practicing.rts.testing

import veeci.practicing.rts.platform.config.AppConfig
import veeci.practicing.rts.platform.config.AppConfigLoader

object TestConfig {
    private val defaults =
        mapOf(
            "APP_ENV" to "test",
            "DB_URL" to "jdbc:postgresql://localhost:5432/unused",
            "DB_USER" to "test",
            "DB_PASSWORD" to "test",
        )

    fun load(overrides: Map<String, String> = emptyMap()): AppConfig = AppConfigLoader.load(defaults + overrides)
}
