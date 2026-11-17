package veeci.practicing.rts.tracking

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.config.RetentionConfig
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.tracking.TrackingSamples
import veeci.practicing.rts.testing.tracking.TrackingSamples.T0
import veeci.practicing.rts.testing.tracking.TrackingSamples.fullFix
import veeci.practicing.rts.testing.tracking.TrackingSamples.newBatch
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedDeviceStatusSampleRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedFixBatchRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedRetentionRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingSessionRepository
import veeci.practicing.rts.tracking.application.LocationRetention
import veeci.practicing.rts.tracking.application.port.out.Purged
import veeci.practicing.rts.tracking.domain.DeviceStatusSample
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReported
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** TC-10-OPS-02: raw location data past retention goes, everything else stays (phase 10c). */
@Testcontainers(disabledWithoutDocker = true)
class RetentionIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }
    private val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    private val sessions = ExposedTrackingSessionRepository()
    private val now = T0 + 100.days

    private fun retention(perRun: Int = 20) =
        LocationRetention(
            ExposedRetentionRepository(),
            transactions,
            Clock.fixed(now.toJavaInstant(), ZoneOffset.UTC),
            RetentionConfig(rawLocation = 90.days, sessionsPerRun = perRun),
        )

    @AfterEach
    fun closePool() = dataSource.close()

    /** A session last seen at [lastSeen], with 2 fixes and 1 status sample. */
    private suspend fun session(lastSeen: Instant): SessionId =
        TrackingSamples.session(at = lastSeen).let { session ->
            transactions.inTransaction {
                sessions.save(session)
                ExposedFixBatchRepository().insert(
                    newBatch(session.id, 1, listOf(fullFix(lastSeen), fullFix(lastSeen + 1.seconds, index = 1))),
                )
                ExposedDeviceStatusSampleRepository().insert(
                    DeviceStatusSample(session.id, lastSeen, DeviceStatusReported.Battery(50, false, null, null), "STILL"),
                )
            }
            session.id
        }

    @Test
    fun `TC-10-OPS-02 fixes and samples of sessions last seen over 90 days ago are deleted, the rest stays, once`() =
        runTest {
            val old = session(lastSeen = T0) // 100 days before now
            val recent = session(lastSeen = now - 30.days)

            retention().purgeExpired() shouldBe Purged(fixes = 2, statusSamples = 1)
            retention().purgeExpired() shouldBe Purged.NONE // purged sessions are not looked at again

            count("fixes", old) shouldBe 0
            count("device_status_samples", old) shouldBe 0
            count("fix_batches", old) shouldBe 1 // counts only, no positions: kept for summaries and health
            count("fixes", recent) shouldBe 2
            count("device_status_samples", recent) shouldBe 1
            (transactions.inTransaction { sessions.find(old) } != null) shouldBe true // the session stays
        }

    @Test
    fun `one run purges at most the configured number of sessions, oldest first`() =
        runTest {
            val oldest = session(lastSeen = T0)
            val older = session(lastSeen = T0 + 1.days)

            retention(perRun = 1).purgeExpired().fixes shouldBe 2

            count("fixes", oldest) shouldBe 0
            count("fixes", older) shouldBe 2
        }

    private fun count(
        table: String,
        session: SessionId,
    ): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $table WHERE session_id = ?").use { statement ->
                statement.setObject(1, session.value)
                statement.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }
}
