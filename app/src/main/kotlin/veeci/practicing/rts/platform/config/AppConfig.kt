package veeci.practicing.rts.platform.config

import com.sksamuel.hoplite.Secret
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class AppEnv { DEV, TEST, STAGING, PROD }

data class AppConfig(
    val app: AppInfo,
    val http: HttpConfig,
    val db: DBConfig,
    val ws: WsConfig,
    val jwt: JwtConfig,
    val tracking: TrackingConfig,
    val events: EventsConfig,
    val commands: CommandsConfig = CommandsConfig(),
    val routing: RoutingConfig = RoutingConfig(),
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
    /** On stop: how long the server must be idle (no new work) before it exits early. */
    val shutdownGracePeriod: Duration,
    /** On stop: the most time in-flight requests get to finish. Keep it below the platform's kill timeout. */
    val shutdownTimeout: Duration,
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

/** Access tokens: JSON Web Tokens signed with HMAC-SHA256. */
data class JwtConfig(
    /** The signing key, at least 32 characters. Set it with the JWT_SECRET environment variable, never in a file in git. */
    val secret: Secret,
    /** Written into every token (`iss`) and required when a token is checked. */
    val issuer: String,
    /** Written into every token (`aud`) and required when a token is checked. */
    val audience: String,
    /** How long an issued token is valid. */
    val tokenTtl: Duration,
    /** Clock difference between servers that is tolerated when `exp` and `iat` are checked. */
    val leeway: Duration,
)

/** Delivery of the events in the outbox to the contexts that react to them. */
data class EventsConfig(
    /** How long the relay waits between two reads of the outbox when it found nothing to deliver. */
    val relayInterval: Duration,
    /** The most events the relay delivers in one transaction. */
    val relayBatchSize: Int,
    /** A driver silent for longer than this is reported as gone to guests (phase 4). */
    val staleAfter: Duration = 60.seconds,
    /** How often the stale-driver job looks for silent drivers; "back" shows within this time. */
    val staleCheckEvery: Duration = 10.seconds,
)

/** Device commands (phase 5). */
data class CommandsConfig(
    /** How long a command may wait for the device's ack before it expires and is never sent again. */
    val expireAfter: Duration = 10.minutes,
    /** How often the expiry job looks for overdue commands. */
    val expiryCheckEvery: Duration = 30.seconds,
)

/** Road routing (phase 6). */
data class RoutingConfig(
    /** The OSM XML extract the road graph is built from, relative to the working directory (docs/data/osm.md). */
    val graphPath: String = "data/osm/hanoi.osm",
    /** A point farther than this from every road gets NO_ROAD_NEARBY. */
    val maxSnapDistanceM: Double = 300.0,
    /** A point farther than this outside the map's bounding box gets OUTSIDE_COVERAGE. */
    val coverageMarginM: Double = 1_000.0,
    /** The most time one route may take to plan; a slower one gets 503 ROUTING_TIMEOUT. */
    val planTimeout: Duration = 500.milliseconds,
)

/** The tracking context's limits. The tracking module turns them into its own types. */
data class TrackingConfig(
    /** A driver socket that sends no valid `hello` within this time is closed with 4401. */
    val handshakeTimeout: Duration,
    /** Largest batch accepted; a bigger one gets error{BATCH_TOO_LARGE}. Sent to the client in `welcome`. */
    val maxFixesPerBatch: Int,
    /** A fix with a larger accuracy radius is rejected as POOR_ACCURACY. */
    val maxAccuracyM: Double,
    /** How far in the future a device clock may be before a fix is FUTURE_TIMESTAMP. */
    val maxClockSkew: Duration,
    /** How old a fix may be when it arrives before it is TOO_OLD. */
    val maxAge: Duration,
    /** Faster implied movement between two fixes is an IMPLAUSIBLE_JUMP. */
    val maxSpeedMps: Double,
    /** Whether fixes from a mock location provider are rejected as MOCK_LOCATION. */
    val rejectMock: Boolean,
)
