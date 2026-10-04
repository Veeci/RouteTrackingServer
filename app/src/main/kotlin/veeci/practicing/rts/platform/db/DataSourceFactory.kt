package veeci.practicing.rts.platform.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import veeci.practicing.rts.platform.config.DBConfig

/**
 * The connection pool. Opening a Postgres connection is slow (network + authentication), so a fixed set is
 * opened once and lent out per transaction. Creating it connects immediately: an unreachable database fails
 * startup instead of the first request.
 */
fun createDataSource(
    config: DBConfig,
    metrics: MeterRegistry? = null,
): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            poolName = "rts-db"
            jdbcUrl = config.url
            username = config.user
            password = config.password.value
            maximumPoolSize = config.maxPoolSize
            // Hikari's default is 30 s: far too long for a request (or a health probe) to wait for a connection.
            connectionTimeout = CONNECTION_TIMEOUT_MS
            // Pool gauges (active, idle, pending connections) for the /metrics page.
            metricRegistry = metrics
        },
    )

private const val CONNECTION_TIMEOUT_MS = 5_000L
