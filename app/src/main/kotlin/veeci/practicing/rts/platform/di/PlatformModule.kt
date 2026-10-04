package veeci.practicing.rts.platform.di

import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose
import veeci.practicing.rts.platform.config.AppConfig
import veeci.practicing.rts.platform.db.DatabaseHealthIndicator
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.platform.observability.HealthIndicator
import veeci.practicing.rts.platform.observability.HealthRegistry
import java.time.Clock
import javax.sql.DataSource

/**
 * Technical singletons every context can use. [metrics] is created by the composition root (it also feeds the
 * HTTP metrics plugin and the /metrics page); it is registered here so any component can add its own meters.
 */
fun platformModule(
    config: AppConfig,
    metrics: MeterRegistry,
): Module =
    module {
        single<Clock> { Clock.systemUTC() }
        single<MeterRegistry> { metrics } onClose { it?.close() }

        // Built at startup (fail fast if the database is unreachable), closed when the app stops.
        single<DataSource>(createdAtStart = true) { createDataSource(config.db, metrics) } onClose { (it as? HikariDataSource)?.close() }

        // One attempt per transaction: retrying a failed block could repeat its side effects.
        single { Database.connect(get<DataSource>(), databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 }) }

        single<TransactionRunner> { ExposedTransactionRunner(get()) }

        // Contexts add their own dependencies (Redis, ...) the same way: bind them as HealthIndicator.
        single { DatabaseHealthIndicator(get()) } bind HealthIndicator::class
        single { HealthRegistry(getAll()) } onClose { it?.close() }
    }
