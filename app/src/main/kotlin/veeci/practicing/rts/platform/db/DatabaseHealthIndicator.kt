package veeci.practicing.rts.platform.db

import veeci.practicing.rts.platform.observability.HealthIndicator
import veeci.practicing.rts.platform.observability.HealthStatus
import javax.sql.DataSource

/** UP when a pooled connection can be borrowed and the server answers on it. */
class DatabaseHealthIndicator(
    private val dataSource: DataSource,
) : HealthIndicator {
    override val name = "db"

    override fun check(): HealthStatus =
        dataSource.connection.use { if (it.isValid(VALIDATION_TIMEOUT_SECONDS)) HealthStatus.UP else HealthStatus.DOWN }

    private companion object {
        const val VALIDATION_TIMEOUT_SECONDS = 1
    }
}
