package veeci.practicing.rts.platform.config

import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.sources.EnvironmentVariablesPropertySource

object AppConfigLoader {
    private val profiles = AppEnv.entries.map { it.name.lowercase() }

    @OptIn(ExperimentalHoplite::class) // withExplicitSealedTypes: the mode Hoplite asks everyone to move to
    fun load(env: Map<String, String> = System.getenv()): AppConfig {
        val profile = env["APP_ENV"]?.lowercase() ?: throw ConfigException("APP_ENV is not set. Expected one of $profiles")
        if (profile !in profiles) throw ConfigException("APP_ENV=$profile is unknown. Expected one of $profiles")

        return ConfigLoaderBuilder
            .defaultWithoutPropertySources()
            .addPropertySource(EnvironmentVariablesPropertySource(environmentVariableMap = { env }))
            .withResolveTypesCaseInsensitive()
            .withExplicitSealedTypes() // Hoplite prints a deprecation notice to stdout unless this is on
            .build()
            .loadConfigOrThrow<AppConfig>("/config/$profile.conf", "/config/base.conf")
    }
}
