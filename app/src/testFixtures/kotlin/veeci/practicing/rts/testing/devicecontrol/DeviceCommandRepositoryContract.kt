package veeci.practicing.rts.testing.devicecontrol

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.devicecontrol.application.port.out.DeviceCommandRepository
import veeci.practicing.rts.devicecontrol.domain.CommandId
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.devicecontrol.domain.CommandStatus
import veeci.practicing.rts.devicecontrol.domain.DeviceCommand
import veeci.practicing.rts.devicecontrol.domain.GeofenceRegion
import veeci.practicing.rts.devicecontrol.domain.TrackingAccuracy
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What CommandService relies on from a command repository (TC-5-DB-01), for the in-memory and Postgres versions. */
abstract class DeviceCommandRepositoryContract {
    abstract val commands: DeviceCommandRepository
    open val transactions: TransactionRunner = DirectTransactionRunner

    private suspend fun <T> tx(block: suspend () -> T): T = transactions.inTransaction(block)

    private val t0 = Instant.parse("2026-11-12T08:00:00.123456Z")
    private val driver = UserId(UUID.randomUUID())

    private fun command(
        payload: CommandPayload = CommandPayload.FlushQueue,
        at: Instant = t0,
        driver: UserId = this.driver,
    ) = DeviceCommand.issue(CommandId(UUID.randomUUID()), driver, payload, at, at + 10.minutes).also { it.pullEvents() }

    private fun DeviceCommand.values() = listOf(id, driverId, payload, createdAt, expiresAt, status, updatedAt)

    @Test
    fun `TC-5-DB-01 every payload kind reads back exactly as it was stored`() =
        runTest {
            val stored =
                listOf(
                    command(CommandPayload.SetConstraint(TrackingAccuracy.HIGH, 1000, "guest_watching")),
                    command(CommandPayload.RegisterGeofences(listOf(GeofenceRegion("pickup", GeoPoint(10.7725, 106.698), 150.0)))),
                    command(CommandPayload.FlushQueue),
                )
            stored.forEach { tx { commands.insert(it) } }

            stored.forEach { tx { commands.find(it.id) }!!.values() shouldBe it.values() }
        }

    @Test
    fun `an update saves the status and its time`() =
        runTest {
            val command = command().also { tx { commands.insert(it) } }
            command.markSent(t0 + 5.seconds)
            tx { commands.update(command) }

            val read = tx { commands.find(command.id) }!!
            read.status shouldBe CommandStatus.SENT
            read.updatedAt shouldBe t0 + 5.seconds
        }

    @Test
    fun `open commands of one driver come oldest first, without closed ones or other drivers' ones`() =
        runTest {
            val second = command(at = t0 + 2.seconds)
            val first = command(at = t0 + 1.seconds)
            val applied = command(at = t0).apply { acknowledge(applied = true, t0 + 3.seconds) }
            val someoneElses = command(driver = UserId(UUID.randomUUID()))
            listOf(second, first, applied, someoneElses).forEach { tx { commands.insert(it) } }

            tx { commands.openFor(driver) }.map { it.id } shouldBe listOf(first.id, second.id)
        }

    @Test
    fun `the latest commands come newest first and include closed ones`() =
        runTest {
            val old = command(at = t0).apply { expire(t0 + 1.seconds) }
            val new = command(at = t0 + 1.minutes)
            listOf(old, new).forEach { tx { commands.insert(it) } }

            tx { commands.latestFor(driver, 10) }.map { it.id } shouldBe listOf(new.id, old.id)
            tx { commands.latestFor(driver, 1) }.map { it.id } shouldBe listOf(new.id)
        }

    @Test
    fun `due commands are the open ones whose expiry time has come`() =
        runTest {
            val due = command(at = t0)
            val notYet = command(at = t0 + 5.minutes)
            val closed = command(at = t0).apply { acknowledge(applied = false, t0 + 1.seconds) }
            listOf(due, notYet, closed).forEach { tx { commands.insert(it) } }

            tx { commands.findDue(t0 + 10.minutes, 100) }.map { it.id } shouldBe listOf(due.id)
        }
}
