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
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingSessionRepository
import veeci.practicing.rts.tracking.application.TrackingPresence
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.TrackingSession
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Testcontainers(disabledWithoutDocker = true)
class DriverPresenceIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }
    private val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    private val sessions = ExposedTrackingSessionRepository()
    private val presence = TrackingPresence(sessions, transactions)
    private val t0 = Instant.parse("2026-11-12T08:00:00.123456Z")

    @AfterEach
    fun closePool() = dataSource.close()

    private suspend fun session(
        driver: UserId,
        lastSeen: Instant,
    ) = transactions.inTransaction {
        sessions.save(TrackingSession(SessionId(UUID.randomUUID()), DeviceId("phone"), "0.9.0", t0, lastSeen, driver))
    }

    @Test
    fun `each driver gets the latest last-seen time over their sessions, and unknown drivers are missing`() =
        runTest {
            val dana = UserId(UUID.randomUUID())
            val eli = UserId(UUID.randomUUID())
            val nobody = UserId(UUID.randomUUID())
            session(dana, t0 + 1.minutes)
            session(dana, t0 + 5.minutes)
            session(eli, t0 + 2.minutes)

            presence.lastSeen(listOf(dana, eli, nobody)) shouldBe mapOf(dana to t0 + 5.minutes, eli to t0 + 2.minutes)
            presence.lastSeen(emptyList()) shouldBe emptyMap()
        }
}
