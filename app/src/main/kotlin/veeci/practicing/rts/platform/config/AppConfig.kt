package veeci.practicing.rts.platform.config

import com.sksamuel.hoplite.Secret

enum class AppEnv { DEV, TEST, STAGING, PROD }

data class AppConfig(
    val app: AppInfo,
    val http: HttpConfig,
    val db: DBConfig,
)

data class AppInfo(
    val env: AppEnv,
    val version: String,
)

data class HttpConfig(
    val port: Int,
    /** Largest request body accepted, in bytes; bigger ones get 413. */
    val maxBodyBytes: Long,
    /** Requests one client IP may make per minute; more get 429. */
    val rateLimitPerMinute: Int,
)

data class DBConfig(
    val url: String,
    val user: String,
    val password: Secret,
    val maxPoolSize: Int,
    val migrateOnStart: Boolean,
)
