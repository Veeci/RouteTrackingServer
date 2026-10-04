package veeci.practicing.rts.platform.config

import com.sksamuel.hoplite.Secret
import kotlin.time.Duration

enum class AppEnv { DEV, TEST, STAGING, PROD }

data class AppConfig(
    val app: AppInfo,
    val http: HttpConfig,
    val db: DBConfig,
    val ws: WsConfig,
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

data class WsConfig(
    /** How often the server pings each socket to keep proxies from dropping idle connections. */
    val pingPeriod: Duration,
    /** No pong for this long after a ping: the connection is considered dead and closed. */
    val timeout: Duration,
    /** Largest frame accepted; a bigger one closes the session with 1009. */
    val maxFrameBytes: Long,
    /** Messages one session may send per second; extra ones are dropped with error{RATE_LIMITED}. */
    val messagesPerSecond: Int,
)
