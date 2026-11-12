package veeci.practicing.rts.devicecontrol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.devicecontrol.application.CommandService
import veeci.practicing.rts.devicecontrol.domain.CommandError
import veeci.practicing.rts.devicecontrol.domain.CommandId
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.devicecontrol.domain.CommandStatus
import veeci.practicing.rts.devicecontrol.domain.TrackingAccuracy
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.testing.devicecontrol.InMemoryCommandEventOutbox
import veeci.practicing.rts.testing.devicecontrol.InMemoryDeviceCommandRepository
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class CommandServiceTest {
    /** A clock the test moves forward. */
    private class MovingClock(
        var now: Instant = Instant.parse("2026-11-12T08:00:00Z"),
    ) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId?) = this

        fun advance(by: kotlin.time.Duration) {
            now += by.toJavaDuration()
        }
    }

    private val repository = InMemoryDeviceCommandRepository()
    private val outbox = InMemoryCommandEventOutbox()
    private val clock = MovingClock()
    private val service = CommandService(repository, outbox, DirectTransactionRunner, clock, expireAfter = 10.minutes)
    private val driver = UserId(UUID.randomUUID())
    private val admin = Actor(UserId(UUID.randomUUID()), Role.ADMIN)
    private val high = CommandPayload.SetConstraint(TrackingAccuracy.HIGH, 1000, "guest_watching")

    @Test
    fun `TC-5-CMD-02 a command for an offline driver is stored PENDING, announced, and waits for delivery`() =
        runTest {
            val command = service.issue(driver, high, admin)

            command.status shouldBe CommandStatus.PENDING
            outbox.events.single().commandId shouldBe command.id.value
            outbox.events.single().kind shouldBe "SET_CONSTRAINT"
            service.pendingFor(driver).map { it.id } shouldBe listOf(command.id)
        }

    @Test
    fun `TC-5-CMD-03 an applied ack marks it APPLIED, and a second identical ack changes nothing and is no error`() =
        runTest {
            val command = service.issue(driver, CommandPayload.FlushQueue, admin)
            service.markSent(command.id)

            service.acknowledge(command.id, driver, applied = true).status shouldBe CommandStatus.APPLIED
            val appliedAt = repository.find(command.id)!!.updatedAt
            clock.advance(5.seconds)
            val again = service.acknowledge(command.id, driver, applied = true)

            again.status shouldBe CommandStatus.APPLIED
            repository.find(command.id)!!.updatedAt shouldBe appliedAt
            service.pendingFor(driver).shouldBeEmpty()
        }

    @Test
    fun `TC-5-CMD-04 an ack for an unknown id, or for another driver's command, is COMMAND_NOT_FOUND`() =
        runTest {
            val command = service.issue(driver, CommandPayload.FlushQueue, admin)

            shouldThrow<DomainException> { service.acknowledge(CommandId(UUID.randomUUID()), driver, true) }.code shouldBe
                CommandError.COMMAND_NOT_FOUND
            shouldThrow<DomainException> { service.acknowledge(command.id, UserId(UUID.randomUUID()), true) }.code shouldBe
                CommandError.COMMAND_NOT_FOUND
        }

    @Test
    fun `TC-5-CMD-05 an overdue command expires and is never offered for delivery again`() =
        runTest {
            val command = service.issue(driver, CommandPayload.FlushQueue, admin)
            clock.advance(10.minutes)

            service.pendingFor(driver).shouldBeEmpty() // overdue already, before the job ran
            service.expireDue() shouldBe 1

            repository.find(command.id)!!.status shouldBe CommandStatus.EXPIRED
            service.expireDue() shouldBe 0
        }

    @Test
    fun `TC-5-CMD-06 a new constraint expires older unsent constraints, and leaves other kinds and sent ones alone`() =
        runTest {
            val unsent = service.issue(driver, high, admin)
            clock.advance(1.seconds)
            val sent = service.issue(driver, high, admin).also { service.markSent(it.id) }
            clock.advance(1.seconds)
            val flush = service.issue(driver, CommandPayload.FlushQueue, admin)
            clock.advance(1.seconds)

            val newest = service.issue(driver, high.copy(maxAccuracy = TrackingAccuracy.BALANCED), admin)

            repository.find(unsent.id)!!.status shouldBe CommandStatus.EXPIRED
            repository.find(sent.id)!!.status shouldBe CommandStatus.SENT
            service.pendingFor(driver).map { it.id } shouldBe listOf(sent.id, flush.id, newest.id)
        }

    @Test
    fun `TC-5-CMD-07 an ack after expiry leaves the command EXPIRED`() =
        runTest {
            val command = service.issue(driver, CommandPayload.FlushQueue, admin)
            clock.advance(10.minutes)
            service.expireDue()

            service.acknowledge(command.id, driver, applied = true).status shouldBe CommandStatus.EXPIRED
        }

    @Test
    fun `only an admin may issue or list commands`() =
        runTest {
            val guest = Actor(UserId(UUID.randomUUID()), Role.GUEST)

            shouldThrow<DomainException> { service.issue(driver, high, guest) }.code shouldBe CommandError.COMMAND_FORBIDDEN
            shouldThrow<DomainException> { service.list(driver, guest, 10) }.code shouldBe CommandError.COMMAND_FORBIDDEN
            outbox.events.shouldBeEmpty()
        }

    @Test
    fun `a payload the device cannot use is INVALID_COMMAND and nothing is stored`() =
        runTest {
            shouldThrow<DomainException> { service.issue(driver, high.copy(minIntervalMs = -1), admin) }.code shouldBe
                CommandError.INVALID_COMMAND
            shouldThrow<DomainException> { service.issue(driver, CommandPayload.RegisterGeofences(emptyList()), admin) }.code shouldBe
                CommandError.INVALID_COMMAND

            repository.latestFor(driver, 10).shouldBeEmpty()
        }

    @Test
    fun `the server's own policies issue without an actor`() =
        runTest {
            service.issueAsServer(driver, high).status shouldBe CommandStatus.PENDING
        }
}
