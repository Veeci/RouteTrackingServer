package veeci.practicing.rts.platform.db

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/** Brings the schema up to date by applying the pending files in `resources/db/migration`, in version order. */
object Migrations {
    private val log = LoggerFactory.getLogger(Migrations::class.java)

    /** Returns how many migrations were applied now; 0 when the schema was already current. */
    fun run(dataSource: DataSource): Int {
        val result =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        log.info("Schema at version {} ({} migration(s) applied now)", result.targetSchemaVersion, result.migrationsExecuted)
        return result.migrationsExecuted
    }
}
