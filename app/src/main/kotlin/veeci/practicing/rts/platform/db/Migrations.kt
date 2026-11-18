package veeci.practicing.rts.platform.db

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/** Brings the schema up to date by applying the pending files in `resources/db/migration`, in version order. */
object Migrations {
    private val log = LoggerFactory.getLogger(Migrations::class.java)

    /** Where the migration files are. Tests pass other folders, for example one with a migration that fails. */
    val DEFAULT_LOCATIONS = listOf("classpath:db/migration")

    /** Returns how many migrations were applied now; 0 when the schema was already current. */
    @Suppress("SpreadOperator") // Flyway takes a vararg; the copy of a one-item list happens once per process
    fun run(
        dataSource: DataSource,
        locations: List<String> = DEFAULT_LOCATIONS,
    ): Int {
        val flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations(*locations.toTypedArray())
                .load()
        val applied = flyway.migrate().migrationsExecuted
        // The migrate result only names a version when something was applied; ask for the current one instead.
        val current =
            flyway
                .info()
                .current()
                ?.version
                ?.version ?: "none"
        log.info("Schema at version {} ({} migration(s) applied now)", current, applied)
        return applied
    }
}
