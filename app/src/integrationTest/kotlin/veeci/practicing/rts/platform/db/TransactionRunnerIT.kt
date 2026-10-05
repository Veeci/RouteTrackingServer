package veeci.practicing.rts.platform.db

import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import java.sql.SQLException

@Testcontainers(disabledWithoutDocker = true)
class TransactionRunnerIT {
    /** A throwaway table that exists only in this test's fresh database. */
    private object Probe : Table("tx_probe") {
        val id = integer("id")
        override val primaryKey = PrimaryKey(id)
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var runner: TransactionRunner

    @BeforeEach
    fun setUp() {
        dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db)
        val database = Database.connect(dataSource)
        transaction(database) { SchemaUtils.create(Probe) }
        runner = ExposedTransactionRunner(database)
    }

    @AfterEach
    fun tearDown() = dataSource.close()

    @Test
    fun `committed writes are visible afterwards`() =
        runTest {
            runner.inTransaction { Probe.insert { it[id] = 1 } }

            rowCount() shouldBe 1
        }

    @Test
    fun `TC-1-TX-01 a failing write rolls back the writes before it`() =
        runTest {
            shouldThrow<SQLException> {
                runner.inTransaction {
                    Probe.insert { it[id] = 1 }
                    Probe.insert { it[id] = 1 } // duplicate primary key: Postgres rejects it
                }
            }

            rowCount() shouldBe 0
        }

    @Test
    fun `TC-1-TX-02 a nested call joins the outer transaction and rolls back with it`() =
        runTest {
            shouldThrow<IllegalStateException> {
                runner.inTransaction {
                    Probe.insert { it[id] = 1 }
                    runner.inTransaction { Probe.insert { it[id] = 2 } } // returns normally...
                    error("the outer block fails after the nested call finished")
                }
            }

            rowCount() shouldBe 0 // ...yet id=2 is gone too: it was never committed on its own
        }

    private suspend fun rowCount(): Long = runner.inTransaction { Probe.selectAll().count() }
}
