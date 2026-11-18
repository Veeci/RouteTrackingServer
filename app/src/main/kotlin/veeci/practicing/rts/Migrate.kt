package veeci.practicing.rts

import com.sksamuel.hoplite.ConfigException
import org.flywaydb.core.api.FlywayException
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.config.AppConfigLoader
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("veeci.practicing.rts.Migrate")

/** Flyway holds one connection for its schema history lock and runs the migrations on a second one. */
private const val FLYWAY_CONNECTIONS = 2

/**
 * `/app/bin/migrate` in the image (phase 1.5, T1b.2): applies the pending migrations and exits. A deploy runs it before
 * the new app starts, so a failed migration stops the deploy while the old version keeps serving. It reads the same
 * environment as the app, and starts neither Koin nor Ktor.
 */
fun main() {
    exitProcess(migrate())
}

/**
 * Returns the process exit code: 0 when the schema is current, 1 when the config is invalid or a migration failed. Any
 * other failure (an unreachable database) is thrown, and the JVM then exits with 1 as well.
 */
fun migrate(
    env: Map<String, String> = System.getenv(),
    locations: List<String> = Migrations.DEFAULT_LOCATIONS,
): Int =
    try {
        val config = AppConfigLoader.load(env)
        createDataSource(config.db, size = FLYWAY_CONNECTIONS).use { Migrations.run(it, locations) }
        0
    } catch (e: ConfigException) {
        log.error("Invalid configuration, nothing migrated:\n{}", e.message)
        1
    } catch (e: FlywayException) {
        log.error("Migration failed; the schema stays at the last version that succeeded", e)
        1
    }
