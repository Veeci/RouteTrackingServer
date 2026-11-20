package veeci.practicing.rts.platform.config

import com.sksamuel.hoplite.Secret
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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
    val telemetry: TelemetryConfig = TelemetryConfig(),
    val redis: RedisConfig = RedisConfig(),
    val retention: RetentionConfig = RetentionConfig(),
    val admin: AdminConfig = AdminConfig(),
    val fleet: FleetConfig = FleetConfig(),
    val staging: StagingConfig = StagingConfig(),
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
    /**
     * Whether `X-Forwarded-For` and `X-Forwarded-Proto` set the client address and scheme. Only a peer on loopback is
     * trusted to send them, so a client that connects directly cannot pick its own address. Off by default: turn it on
     * (`HTTP_TRUSTFORWARDEDHEADERS=true`) only behind a proxy that is known to set these headers (phase 1.5, T1b.4).
     */
    val trustForwardedHeaders: Boolean = false,
    /**
     * The port of the admin plane (phase 12a): the `/api/v1/admin` routes and the `/ws/v1/admin` sockets answer only
     * there, and the public [port] answers 404 for them. Every other route answers on both ports. 0, the default,
     * keeps everything on [port], as in dev and test. Staging sets it (`HTTP_ADMINPORT=8081`) and publishes that port
     * on loopback only, so the admin API never goes through Funnel.
     */
    val adminPort: Int = 0,
) {
    /** Whether the admin plane has a port of its own. */
    val splitsAdminPort: Boolean get() = adminPort != 0
}

data class DBConfig(
    val url: String,
    val user: String,
    val password: Secret,
    val maxPoolSize: Int,
    val migrateOnStart: Boolean,
    /** The outbox relay's own pool, on top of [maxPoolSize]. It runs one transaction at a time; one spare. */
    val relayPoolSize: Int = 2,
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
    /** How long a token of staff (ADMIN, OPERATOR) is valid: shorter, because it can do more (phase 11). */
    val staffTokenTtl: Duration = 15.minutes,
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

/**
 * Redis, for running several instances (phase 10). Without [url] the app runs alone: live updates and driver
 * messages stay in this process. Set `REDIS_URL` (for example `redis://localhost:6379`) to share them.
 */
data class RedisConfig(
    val url: String? = null,
    /** How long a publish may take before it is given up: live delivery is best effort. */
    val publishTimeout: Duration = 500.milliseconds,
    /** How long a trip's latest update stays for guests who subscribe later. */
    val lastUpdateTtl: Duration = 1.hours,
) {
    /** A blank URL counts as none, so compose can pass `REDIS_URL=` when Redis is off. */
    val enabled: Boolean get() = !url.isNullOrBlank()
}

/**
 * How long raw location data is kept (phase 10): GPS fixes and device status samples. Trips, summaries, moments,
 * routes and session health stay. Location minimisation: what is not needed after a while is not kept.
 */
data class RetentionConfig(
    val rawLocation: Duration = 90.days,
    /** How often the retention job looks for sessions to purge. */
    val checkEvery: Duration = 2.minutes,
    /** At most this many sessions per run, in one transaction, so no run holds locks for long. */
    val sessionsPerRun: Int = 20,
)

/** The admin dashboard, a browser app on another origin (phase 11c). */
data class AdminConfig(
    /**
     * Origins allowed to call `/api/v1/admin` from a browser, for example `https://admin.example.com`. Set them with
     * `ADMIN_ORIGINS`, comma separated. Empty turns CORS off: browsers then refuse cross-origin calls.
     */
    val origins: List<String> = emptyList(),
)

/** The live fleet socket (phase 11c). */
data class FleetConfig(
    /** How often each instance reads the changed drivers and sends every subscriber its delta. */
    val tick: Duration = 2.seconds,
    /** More drivers than this in a subscriber's area: it gets cluster counts instead of markers. */
    val maxMarkers: Int = 5_000,
    /** A socket that takes longer than this to accept one message is closed with 1013. */
    val sendTimeout: Duration = 5.seconds,
)

/**
 * The staging environment (phase 1.5). `STAGING_ACCESS_TOKEN` sets [StagingAccess.token]: Hoplite splits environment
 * variable names at each underscore, so the variable maps to `staging.access.token`.
 */
data class StagingConfig(
    val access: StagingAccess = StagingAccess(),
)

data class StagingAccess(
    /**
     * Testers send it in `X-Staging-Token` to reach the routes under `/api/v1/dev` on staging. Required, with at least
     * [MIN_STAGING_TOKEN_LENGTH] characters, when `APP_ENV=staging`. Unused elsewhere.
     */
    val token: Secret? = null,
)

/** A staging access token shorter than this refuses to start: 32 characters of hex are 128 bits. */
const val MIN_STAGING_TOKEN_LENGTH = 32

/** Session health (phase 9). */
data class TelemetryConfig(
    /** How often the health job looks for idle sessions. */
    val computeEvery: Duration = 5.minutes,
    /** A session with no message for this long is computed. A session that resumes is computed again later. */
    val idleAfter: Duration = 10.minutes,
    /** At most this many sessions per run; a backlog drains over the next runs. */
    val batchSize: Int = 100,
    /** The fix interval a device keeps when no applied SET_CONSTRAINT says otherwise. */
    val defaultInterval: Duration = 5.seconds,
)

/** Road routing (phase 6). */
data class RoutingConfig(
    /** The OSM XML extract the road graph is built from, relative to the working directory (docs/data/osm.md). */
    val graphPath: String = "data/osm/hanoi.osm",
    /**
     * Where the binary graph cache goes, `<dir>/<osm file name>.graph`. A start whose OSM file matches the cache
     * skips the XML parse. Empty turns the cache off.
     */
    val graphCacheDir: String = "data/osm/cache",
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
