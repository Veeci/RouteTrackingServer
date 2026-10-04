package veeci.practicing.rts.testing

import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

/**
 * One Postgres container for the whole test run: starting a container takes seconds, so it is started
 * once, on first use, and removed when the JVM exits. Tests that need a blank slate get their own empty
 * database inside it, which takes milliseconds.
 */
object TestDatabase {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }

    /** Env vars pointing the app at the container's shared database. */
    fun env(): Map<String, String> = envFor(container.databaseName)

    /** Creates a new, empty database and returns env vars pointing at it. */
    fun freshDatabaseEnv(): Map<String, String> {
        val name = "test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return envFor(name)
    }

    private fun envFor(database: String): Map<String, String> =
        mapOf(
            "DB_URL" to "jdbc:postgresql://${container.host}:${container.getMappedPort(POSTGRES_PORT)}/$database",
            "DB_USER" to container.username,
            "DB_PASSWORD" to container.password,
        )

    private const val POSTGRES_PORT = 5432
}
