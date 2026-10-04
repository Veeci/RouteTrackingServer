package veeci.practicing.rts.platform.di

import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import veeci.practicing.rts.platform.config.AppConfig
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.db.createDataSource
import java.time.Clock
import javax.sql.DataSource

/** Technical singletons every context can use. Later steps add health checks. */
fun platformModule(config: AppConfig): Module =
    module {
        single<Clock> { Clock.systemUTC() }

        // Built at startup (fail fast if the database is unreachable), closed when the app stops.
        single<DataSource>(createdAtStart = true) { createDataSource(config.db) } onClose { (it as? HikariDataSource)?.close() }

        // One attempt per transaction: retrying a failed block could repeat its side effects.
        single { Database.connect(get<DataSource>(), databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 }) }

        single<TransactionRunner> { ExposedTransactionRunner(get()) }
    }
