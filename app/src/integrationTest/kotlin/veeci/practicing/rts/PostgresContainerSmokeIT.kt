package veeci.practicing.rts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Proves the integration suite can start a real Postgres. Replaced by the shared container in phase 1. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresContainerSmokeIT {
    @Test
    fun `postgres container accepts queries`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().executeQuery("select 1").use { rows ->
                rows.next()
                rows.getInt(1) shouldBe 1
            }
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
