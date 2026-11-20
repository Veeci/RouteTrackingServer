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
            .also(::checkStaging)
            .also { checkAdminPort(it.http) }
    }

    /**
     * The admin port is 0 or a port of its own. Equal to the public port it would hide nothing: the public port would
     * then serve the admin routes too.
     */
    private fun checkAdminPort(http: HttpConfig) {
        val admin = http.adminPort
        if (admin != 0 && (admin !in 1..MAX_PORT || admin == http.port)) {
            throw ConfigException(
                "HTTP_ADMINPORT=$admin must be 0 (admin routes on the public port) or a port from 1 to $MAX_PORT" +
                    " other than HTTP_PORT=${http.port}",
            )
        }
    }

    private const val MAX_PORT = 65_535

    /** Staging serves the dev token route to the internet, so it must not start without a strong access token. */
    private fun checkStaging(config: AppConfig) {
        if (config.app.env != AppEnv.STAGING) return
        val token =
            config.staging.access.token
                ?.value
                .orEmpty()
        if (token.length < MIN_STAGING_TOKEN_LENGTH) {
            throw ConfigException(
                "APP_ENV=staging needs STAGING_ACCESS_TOKEN with at least $MIN_STAGING_TOKEN_LENGTH characters" +
                    " (for example: openssl rand -hex 24)",
            )
        }
    }
}
