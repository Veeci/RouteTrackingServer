package veeci.practicing.rts.telemetry

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.devicecontrol.adapter.out.persistence.ExposedDeviceCommandRepository
import veeci.practicing.rts.devicecontrol.application.ConstraintHistory
import veeci.practicing.rts.devicecontrol.domain.CommandId
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.devicecontrol.domain.DeviceCommand
import veeci.practicing.rts.devicecontrol.domain.TrackingAccuracy
import veeci.practicing.rts.platform.audit.ExposedAuditLog
import veeci.practicing.rts.platform.config.TelemetryConfig
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.telemetry.adapter.out.persistence.ExposedSessionHealthRepository
import veeci.practicing.rts.telemetry.application.SessionHealthService
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.tracking.TrackingSamples
import veeci.practicing.rts.testing.tracking.TrackingSamples.DRIVER
import veeci.practicing.rts.testing.tracking.TrackingSamples.T0
import veeci.practicing.rts.testing.tracking.TrackingSamples.fullFix
import veeci.practicing.rts.testing.tracking.TrackingSamples.newBatch
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedDeviceStatusSampleRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedFixBatchRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedSessionTelemetryRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingSessionRepository
import veeci.practicing.rts.tracking.application.TrackingTelemetry
import veeci.practicing.rts.tracking.domain.DeviceInfo
import veeci.practicing.rts.tracking.domain.DeviceStatusSample
import veeci.practicing.rts.tracking.domain.Rejection
import veeci.practicing.rts.tracking.domain.RejectionReason
import veeci.practicing.rts.tracking.domain.TrackingSession
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReported
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** Session health on Postgres (phase 9b): the stored record, the job's watermark, and a resumed session. */
@Testcontainers(disabledWithoutDocker = true)
class SessionHealthJobIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }
    private val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    private val sessions = ExposedTrackingSessionRepository()
    private val batches = ExposedFixBatchRepository()
    private val samples = ExposedDeviceStatusSampleRepository()
    private val commands = ExposedDeviceCommandRepository()
    private val health = ExposedSessionHealthRepository()
    private val clock = MovableClock(T0)
    private val admin = Actor(UserId(UUID.randomUUID()), Role.ADMIN)

    private val service =
        SessionHealthService(
            TrackingTelemetry(sessions, samples, ExposedSessionTelemetryRepository(), transactions),
            ConstraintHistory(commands, transactions),
            health,
            transactions,
            clock,
            TelemetryConfig(idleAfter = 10.minutes, batchSize = 2, defaultInterval = 5.seconds),
            ExposedAuditLog(clock),
        )

    @AfterEach
    fun closePool() = dataSource.close()

    private suspend fun session(lastSeen: Duration): TrackingSession =
        TrackingSamples.session().also {
            it.touch(T0 + lastSeen)
            transactions.inTransaction { sessions.save(it) }
        }

    @Test
    fun `TC-9-DB-01 a computed health is stored and reads back with every value`() =
        runTest {
            val session = TrackingSamples.session()
            session.reconnect(
                session.deviceId,
                DRIVER,
                "0.10.0",
                T0 + 10.minutes,
                DeviceInfo("Google", "Pixel 7", 35, "LineageOS 22.1", false),
            )
            val constraint =
                DeviceCommand
                    .issue(
                        CommandId(UUID.randomUUID()),
                        DRIVER,
                        CommandPayload.SetConstraint(TrackingAccuracy.HIGH, 60_000, "t"),
                        T0,
                        T0 + 5.minutes,
                    ).also { it.acknowledge(applied = true, T0 - 1.minutes) }
            transactions.inTransaction {
                sessions.save(session)
                // Fixes at T0 and T0 + 2 min: one 120 s interval, under 3 × the applied 60 s, so no gap.
                batches.insert(newBatch(session.id, 1, listOf(fullFix(T0)), listOf(Rejection(1, RejectionReason.POOR_ACCURACY))))
                batches.insert(newBatch(session.id, 2, listOf(fullFix(T0 + 2.minutes))))
                samples.insert(DeviceStatusSample(session.id, T0, DeviceStatusReported.Battery(80, false, null, null), "STILL"))
                samples.insert(DeviceStatusSample(session.id, T0 + 1.minutes, DeviceStatusReported.Battery(79, false, null, null), "STILL"))
                commands.insert(constraint)
            }
            clock.now = T0 + 30.minutes

            val computed = service.recompute(session.id.value, admin)

            transactions.inTransaction { health.find(session.id.value) } shouldBe computed
            computed.device!!.rom shouldBe "LineageOS 22.1"
            computed.sessionLastSeenAt shouldBe T0 + 10.minutes
            computed.health.fixCount shouldBe 2
            computed.health.acceptedRatio shouldBe 2.0 / 3
            computed.health.primaryProvider shouldBe "AOSP_GPS"
            computed.health.intervalP50S shouldBe 120.0
            computed.health.gaps shouldBe emptyList()
        }

    @Test
    fun `the job computes idle sessions in pages, once each, and a resumed session again`() =
        runTest {
            val first = session(lastSeen = 0.minutes)
            val second = session(lastSeen = 1.minutes)
            val third = session(lastSeen = 2.minutes)
            val live = session(lastSeen = 20.minutes)
            clock.now = T0 + 25.minutes // idle means last seen at or before T0 + 15 min

            service.computeIdle() shouldBe 2
            service.computeIdle() shouldBe 1
            service.computeIdle() shouldBe 0
            computed().shouldContainExactlyInAnyOrder(first.id.value, second.id.value, third.id.value)

            first.touch(T0 + 20.minutes) // a late batch: the session resumed
            transactions.inTransaction { sessions.save(first) }
            clock.now = T0 + 40.minutes

            service.computeIdle() shouldBe 2 // live and first, both last seen at T0 + 20 min
            computed().shouldContainExactlyInAnyOrder(first.id.value, second.id.value, third.id.value, live.id.value)
            transactions.inTransaction { health.find(first.id.value) }!!.sessionLastSeenAt shouldBe T0 + 20.minutes
        }

    @Test
    fun `an admin's recompute of a live session leaves the job's watermark where it was`() =
        runTest {
            val idle = session(lastSeen = 0.minutes)
            val live = session(lastSeen = 20.minutes)
            clock.now = T0 + 25.minutes

            service.recompute(live.id.value, admin)

            transactions.inTransaction { health.watermark() }.shouldBeNull()
            service.computeIdle() shouldBe 1
            computed().shouldContainExactlyInAnyOrder(idle.id.value, live.id.value)
        }

    /** The sessions that have a stored health. */
    private fun computed(): List<UUID> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT session_id FROM session_health").use { rows ->
                    generateSequence { if (rows.next()) rows.getObject(1, UUID::class.java) else null }.toList()
                }
            }
        }

    /** A clock the test moves by hand. */
    private class MovableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): java.time.Instant = now.toJavaInstant()
    }
}
