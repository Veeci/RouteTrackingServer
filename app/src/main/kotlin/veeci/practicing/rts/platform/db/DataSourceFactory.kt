package veeci.practicing.rts.platform.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import veeci.practicing.rts.platform.config.DBConfig

/**
 * The connection pool. Opening a Postgres connection is slow (network + authentication), so a fixed set is
 * opened once and lent out per transaction. Creating it connects immediately: an unreachable database fails
 * startup instead of the first request.
 *
 * The application uses two pools per instance: [MAIN_POOL] for requests and jobs, and [RELAY_POOL] for the outbox
 * relay alone, so that the relay never queues behind ingest for a connection (load check 11).
 */
fun createDataSource(
    config: DBConfig,
    metrics: MeterRegistry? = null,
    name: String = MAIN_POOL,
    size: Int = config.maxPoolSize,
): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            poolName = name
            jdbcUrl = config.url
            username = config.user
            password = config.password.value
            maximumPoolSize = size
            // Hikari's default is 30 s: far too long for a request (or a health probe) to wait for a connection.
            connectionTimeout = CONNECTION_TIMEOUT_MS
            // Pool gauges (active, idle, pending connections) for the /metrics page, tagged with the pool name.
            metricRegistry = metrics
        },
    )

const val MAIN_POOL = "rts-db"
const val RELAY_POOL = "rts-db-relay"

private const val CONNECTION_TIMEOUT_MS = 5_000L
