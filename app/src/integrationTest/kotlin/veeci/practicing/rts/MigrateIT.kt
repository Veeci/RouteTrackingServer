package veeci.practicing.rts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.testing.TEST_JWT_SECRET
import veeci.practicing.rts.testing.TestDatabase
import java.sql.DriverManager

/** The `bin/migrate` entry point (phase 1.5, T1b.2), called through [migrate], which returns the exit code. */
@Testcontainers(disabledWithoutDocker = true)
class MigrateIT {
    private val database = TestDatabase.freshDatabaseEnv()
    private val env = database + mapOf("APP_ENV" to "test", "JWT_SECRET" to TEST_JWT_SECRET)

    @Test
    fun `TC-1b-MIG-01 migrate applies every migration to an empty database and exits 0`() {
        migrate(env) shouldBe 0

        latestVersion() shouldBe "23"
        migrate(env) shouldBe 0 // nothing pending: still a success
    }

    @Test
    fun `TC-1b-MIG-01 a failing migration exits non-zero and leaves the earlier versions applied`() {
        val withBrokenMigration = Migrations.DEFAULT_LOCATIONS + "classpath:db/failing-migration"

        migrate(env, withBrokenMigration) shouldBe 1

        latestVersion() shouldBe "23"
    }

    @Test
    fun `invalid configuration exits non-zero without touching the database`() {
        migrate(env - "APP_ENV") shouldBe 1
    }

    private fun latestVersion(): String? =
        DriverManager.getConnection(database.getValue("DB_URL"), database.getValue("DB_USER"), database.getValue("DB_PASSWORD")).use {
            it.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
                    .use { rows -> if (rows.next()) rows.getString("version") else null }
            }
        }
}
