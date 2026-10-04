package veeci.practicing.rts.platform.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import veeci.practicing.rts.platform.config.DBConfig

/**
 * The connection pool. Opening a Postgres connection is slow (network + authentication), so a fixed set is
 * opened once and lent out per transaction. Creating it connects immediately: an unreachable database fails
 * startup instead of the first request.
 */
fun createDataSource(config: DBConfig): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            poolName = "rts-db"
            jdbcUrl = config.url
            username = config.user
            password = config.password.value
            maximumPoolSize = config.maxPoolSize
        },
    )
