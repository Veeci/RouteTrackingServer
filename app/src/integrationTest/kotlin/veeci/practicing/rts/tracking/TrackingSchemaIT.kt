package veeci.practicing.rts.tracking

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import java.sql.SQLException
import java.util.UUID

/**
 * The rules that migrations V2 and V3 put into the database itself, checked with plain SQL. They hold even
 * if application code has a bug, so they are tested without any application code.
 */
@Testcontainers(disabledWithoutDocker = true)
class TrackingSchemaIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }
    private val session = UUID.randomUUID()

    @BeforeEach
    fun givenSession() {
        update("INSERT INTO tracking_sessions VALUES (?, 'phone-1', '0.9.0', now(), now())", session)
    }

    @AfterEach
    fun closePool() = dataSource.close()

    @Test
    fun `the same session and seq cannot be stored twice`() {
        insertBatch(seq = 1)

        shouldThrow<SQLException> { insertBatch(seq = 1) }.sqlState shouldBe UNIQUE_VIOLATION
    }

    @Test
    fun `a resent batch with ON CONFLICT DO NOTHING writes no row and raises no error`() {
        insertBatch(seq = 1)

        update("INSERT INTO fix_batches VALUES (?, 1, now(), 0, '[]') ON CONFLICT DO NOTHING", session) shouldBe 0
    }

    @Test
    fun `seq must be positive`() {
        shouldThrow<SQLException> { insertBatch(seq = 0) }.sqlState shouldBe CHECK_VIOLATION
    }

    @Test
    fun `a batch needs its session and a fix needs its batch`() {
        shouldThrow<SQLException> {
            update("INSERT INTO fix_batches VALUES (?, 1, now(), 0, '[]')", UUID.randomUUID())
        }.sqlState shouldBe FOREIGN_KEY_VIOLATION

        shouldThrow<SQLException> { insertFix(seq = 99, idx = 0) }.sqlState shouldBe FOREIGN_KEY_VIOLATION
    }

    @Test
    fun `a fix is identified by its batch and its position in the batch`() {
        primaryKey("fix_batches") shouldBe listOf("session_id", "seq")
        primaryKey("fixes") shouldBe listOf("session_id", "seq", "idx")

        insertBatch(seq = 1)
        insertFix(seq = 1, idx = 0)
        shouldThrow<SQLException> { insertFix(seq = 1, idx = 0) }.sqlState shouldBe UNIQUE_VIOLATION
    }

    @Test
    fun `fixes are indexed by session and time for the latest-fix query`() {
        indexDefinition("fixes_session_time") shouldContain "(session_id, recorded_at)"
    }

    @Test
    fun `a new outbox row gets its creation time and starts undelivered`() {
        update("INSERT INTO outbox (event_type, payload) VALUES ('test.Event.v1', '{}')")

        query("SELECT created_at, published_at FROM outbox") { rows ->
            rows.next()
            rows.getTimestamp("created_at").shouldNotBeNull()
            rows.getTimestamp("published_at").shouldBeNull()
        }
        indexDefinition("outbox_unpublished") shouldContain "WHERE (published_at IS NULL)"
    }

    private fun insertBatch(seq: Long) = update("INSERT INTO fix_batches VALUES (?, ?, now(), 0, '[]')", session, seq)

    private fun insertFix(
        seq: Long,
        idx: Int,
    ) = update(
        """
        INSERT INTO fixes (session_id, seq, idx, lat, lng, accuracy_m, recorded_at, provider, distance_from_prev_m, cumulative_m)
        VALUES (?, ?, ?, 10.77, 106.69, 5, now(), 'GMS_FUSED', 0, 0)
        """,
        session,
        seq,
        idx,
    )

    /** The primary key's columns, in key order. */
    private fun primaryKey(table: String): List<String> =
        query(
            """
            SELECT a.attname FROM pg_index i
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
            WHERE i.indrelid = '$table'::regclass AND i.indisprimary
            ORDER BY array_position(i.indkey::int2[], a.attnum)
            """,
        ) { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }

    private fun indexDefinition(name: String): String =
        query("SELECT indexdef FROM pg_indexes WHERE indexname = '$name'") { rows ->
            rows.next()
            rows.getString(1)
        }

    private fun update(
        sql: String,
        vararg args: Any,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
                statement.executeUpdate()
            }
        }

    private fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): T =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery(sql).use(read) }
        }

    private companion object {
        // Postgres error codes (SQLSTATE).
        const val UNIQUE_VIOLATION = "23505"
        const val FOREIGN_KEY_VIOLATION = "23503"
        const val CHECK_VIOLATION = "23514"
    }
}
