package veeci.practicing.rts.tracking

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackHistoryRepository
import veeci.practicing.rts.tracking.application.TrackingHistory
import veeci.practicing.rts.tracking.application.api.TrackCursor
import veeci.practicing.rts.tracking.application.api.TrackPoint
import java.sql.Timestamp
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** The DriverTrack API on Postgres (phase 8a): a driver's fixes by time window, across sessions. */
@Testcontainers(disabledWithoutDocker = true)
class TrackHistoryIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }
    private val track = TrackingHistory(ExposedTrackHistoryRepository(), ExposedTransactionRunner(Database.connect(dataSource)))
    private val driver = UserId(UUID.randomUUID())
    private val t0 = Instant.parse("2026-11-16T08:00:00Z")

    @AfterEach
    fun closePool() = dataSource.close()

    @Test
    fun `TC-8-TRK-01 fixes from two sessions of one driver come back merged in time order`() =
        runTest {
            val first = session(driver)
            val second = session(driver)
            batch(first, seq = 1, times = listOf(t0, t0 + 2.seconds))
            batch(second, seq = 1, times = listOf(t0 + 1.seconds, t0 + 3.seconds))
            batch(session(UserId(UUID.randomUUID())), seq = 1, times = listOf(t0 + 1.seconds)) // another driver

            all().map { it.recordedAt } shouldBe listOf(t0, t0 + 1.seconds, t0 + 2.seconds, t0 + 3.seconds)
        }

    @Test
    fun `TC-8-TRK-02 fixes outside the window are left out, and the end is exclusive`() =
        runTest {
            batch(session(driver), seq = 1, times = listOf(t0 - 1.seconds, t0, t0 + 59.seconds, t0 + 1.minutes))

            all(to = t0 + 1.minutes).map { it.recordedAt } shouldBe listOf(t0, t0 + 59.seconds)
        }

    @Test
    fun `TC-8-TRK-03 two fixes with the same time in two sessions both come back across a page boundary`() =
        runTest {
            batch(session(driver), seq = 1, times = listOf(t0, t0 + 1.seconds))
            batch(session(driver), seq = 1, times = listOf(t0 + 1.seconds, t0 + 2.seconds))

            val pages = mutableListOf<List<TrackPoint>>()
            var after: TrackCursor? = null
            do {
                val page = track.fixes(driver, t0, t0 + 1.minutes, after, limit = 2)
                pages += page
                after = page.lastOrNull()?.cursor
            } while (page.size == 2)

            pages.map { it.size } shouldBe listOf(2, 2, 0)
            pages
                .flatten()
                .map { it.cursor }
                .distinct()
                .size shouldBe 4
        }

    @Test
    fun `rejections are counted by reason for batches received in the window`() =
        runTest {
            val s = session(driver)
            batch(s, seq = 1, times = listOf(t0), received = t0, rejections = listOf("POOR_ACCURACY", "MOCK_LOCATION"))
            batch(s, seq = 2, times = listOf(t0 + 1.seconds), received = t0 + 1.seconds, rejections = listOf("POOR_ACCURACY"))
            batch(s, seq = 3, times = listOf(t0 + 2.minutes), received = t0 + 2.minutes, rejections = listOf("POOR_ACCURACY"))

            track.rejections(driver, t0, t0 + 1.minutes) shouldBe mapOf("POOR_ACCURACY" to 2, "MOCK_LOCATION" to 1)
        }

    private suspend fun all(to: Instant = t0 + 10.minutes) = track.fixes(driver, t0, to, null, limit = 100)

    private fun session(owner: UserId): UUID {
        val id = UUID.randomUUID()
        sql(
            "INSERT INTO tracking_sessions (id, device_id, sdk_version, started_at, last_seen_at, driver_id) VALUES (?, 'phone', '1.0', ?, ?, ?)",
            id,
            ts(t0),
            ts(t0),
            owner.value,
        )
        return id
    }

    private fun batch(
        session: UUID,
        seq: Long,
        times: List<Instant>,
        received: Instant = times.last(),
        rejections: List<String> = emptyList(),
    ) {
        val json = rejections.mapIndexed { i, r -> """{"index":${times.size + i},"reason":"$r"}""" }.joinToString(",", "[", "]")
        sql(
            "INSERT INTO fix_batches (session_id, seq, received_at, accepted_count, rejections) VALUES (?, ?, ?, ?, ?::jsonb)",
            session,
            seq,
            ts(received),
            times.size,
            json,
        )
        times.forEachIndexed { idx, at ->
            sql(
                "INSERT INTO fixes (session_id, seq, idx, lat, lng, accuracy_m, recorded_at, provider, distance_from_prev_m, " +
                    "cumulative_m) VALUES (?, ?, ?, 21.0, 105.8, 5, ?, 'gps', 0, 0)",
                session,
                seq,
                idx,
                ts(at),
            )
        }
    }

    private fun ts(at: Instant) = Timestamp.from(at.toJavaInstant())

    private fun sql(
        statement: String,
        vararg values: Any,
    ) = dataSource.connection.use { connection ->
        connection.prepareStatement(statement).use { ps ->
            values.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.executeUpdate()
        }
    }
}
