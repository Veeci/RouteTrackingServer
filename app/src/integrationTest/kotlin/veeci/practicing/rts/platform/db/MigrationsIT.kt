package veeci.practicing.rts.platform.db

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase

@Testcontainers(disabledWithoutDocker = true)
class MigrationsIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db)

    @Test
    fun `TC-1-MIG-01 TC-2-DB-01 Flyway migrates an empty database through every version`() {
        dataSource.use {
            Migrations.run(it)

            appliedVersions() shouldBe listOf("1", "2", "3")
        }
    }

    @Test
    fun `running migrations again applies nothing`() {
        dataSource.use {
            Migrations.run(it)

            Migrations.run(it) shouldBe 0
        }
    }

    private fun appliedVersions(): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                    .use { rows -> buildList { while (rows.next()) add(rows.getString("version")) } }
            }
        }
}
