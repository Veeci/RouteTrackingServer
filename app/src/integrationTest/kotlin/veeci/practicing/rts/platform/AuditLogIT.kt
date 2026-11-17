package veeci.practicing.rts.platform

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.audit.AuditEntry
import veeci.practicing.rts.platform.audit.AuditFilter
import veeci.practicing.rts.platform.audit.ExposedAuditLog
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import java.sql.SQLException
import java.time.Clock
import java.util.UUID

/** The audit log on Postgres (phase 11a): query filters, all-or-nothing with the action, append-only. */
@Testcontainers(disabledWithoutDocker = true)
class AuditLogIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }
    private val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    private val audit = ExposedAuditLog(Clock.systemUTC())
    private val admin = Actor(UserId(UUID.randomUUID()), Role.ADMIN)

    @AfterEach
    fun closePool() = dataSource.close()

    private fun entry(
        action: String,
        target: String = "d1",
    ) = AuditEntry(admin, action, "DRIVER", target, reason = "test", details = mapOf("kind" to "FLUSH_QUEUE"))

    @Test
    fun `records come back newest first, filtered and paged by id`() =
        runTest {
            transactions.inTransaction { listOf("A", "B", "C").forEach { audit.record(entry(it, target = if (it == "B") "d2" else "d1")) } }

            val all = transactions.inTransaction { audit.find(AuditFilter(), before = null, limit = 10) }
            all.map { it.action } shouldBe listOf("C", "B", "A")
            all.first().details shouldBe mapOf("kind" to "FLUSH_QUEUE")
            all.first().actorRole shouldBe "ADMIN"

            transactions.inTransaction { audit.find(AuditFilter(targetId = "d1"), null, 10) }.map { it.action } shouldBe listOf("C", "A")
            transactions.inTransaction { audit.find(AuditFilter(), before = all[0].id, limit = 1) }.map { it.action } shouldBe listOf("B")
        }

    @Test
    fun `TC-11-AUD-02 an action that fails after its audit row leaves no row`() =
        runTest {
            shouldThrow<IllegalStateException> {
                transactions.inTransaction {
                    audit.record(entry("DRIVER_DISABLED"))
                    error("the action failed after the audit insert")
                }
            }

            count() shouldBe 0
        }

    @Test
    fun `TC-11-AUD-03 the audit log refuses UPDATE, DELETE and TRUNCATE, even for the table's owner`() =
        runTest {
            transactions.inTransaction { audit.record(entry("DRIVER_DISABLED")) }

            listOf("UPDATE audit_log SET reason = 'x'", "DELETE FROM audit_log", "TRUNCATE audit_log").forEach { sql ->
                shouldThrow<SQLException> { execute(sql) }.message shouldContain "append-only"
            }
            count() shouldBe 1
        }

    private fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

    private fun count(): Long =
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM audit_log").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }
}
