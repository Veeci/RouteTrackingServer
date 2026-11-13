package veeci.practicing.rts.devicecontrol.application

import org.slf4j.LoggerFactory
import veeci.practicing.rts.devicecontrol.application.port.out.CommandEventOutbox
import veeci.practicing.rts.devicecontrol.application.port.out.DeviceCommandRepository
import veeci.practicing.rts.devicecontrol.domain.CommandError
import veeci.practicing.rts.devicecontrol.domain.CommandId
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.devicecontrol.domain.CommandStatus
import veeci.practicing.rts.devicecontrol.domain.DeviceCommand
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * The use cases of device commands. Each method is one transaction. Admins issue commands by hand; the server's
 * own policies (phase 5c) use [issueAsServer], with no actor, like auto-arrive.
 */
class CommandService(
    private val commands: DeviceCommandRepository,
    private val outbox: CommandEventOutbox,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    /** How long a command may wait for its ack before it expires. */
    private val expireAfter: Duration,
) {
    private val log = LoggerFactory.getLogger(CommandService::class.java)

    /** An admin sends [payload] to [driver]'s device. */
    suspend fun issue(
        driver: UserId,
        payload: CommandPayload,
        actor: Actor,
    ): DeviceCommand {
        requireAdmin(actor)
        return issueAsServer(driver, payload)
    }

    /**
     * Stores the command and writes CommandIssued to the outbox. A new SetConstraint expires the driver's older
     * SetConstraints that were not sent yet: only the newest constraint matters.
     */
    suspend fun issueAsServer(
        driver: UserId,
        payload: CommandPayload,
    ): DeviceCommand =
        transactions.inTransaction {
            val now = now()
            val command = DeviceCommand.issue(newCommandId(), driver, payload, now, now + expireAfter)
            if (payload is CommandPayload.SetConstraint) {
                commands
                    .openFor(driver)
                    .filter { it.payload is CommandPayload.SetConstraint && it.status == CommandStatus.PENDING }
                    .forEach { older -> if (older.expire(now)) commands.update(older) }
            }
            commands.insert(command)
            command.pullEvents().forEach { outbox.add(it) }
            command
        }

    /**
     * The device's answer. A second ack, or an ack after expiry, changes nothing and is not an error: the device
     * may resend acks after a reconnect. An unknown id, or another driver's command, is COMMAND_NOT_FOUND.
     */
    suspend fun acknowledge(
        id: CommandId,
        driver: UserId,
        applied: Boolean,
    ): DeviceCommand =
        transactions.inTransaction {
            val command = commands.find(id)?.takeIf { it.driverId == driver } ?: throw notFound(id)
            if (command.acknowledge(applied, now())) {
                commands.update(command)
            } else {
                log.warn("Ack for command {} ignored: it is already {}", id.value, command.status)
            }
            command
        }

    /** The command was handed to the device's socket (phase 5b). */
    suspend fun markSent(id: CommandId) {
        transactions.inTransaction {
            val command = commands.find(id) ?: throw notFound(id)
            if (command.markSent(now())) commands.update(command)
        }
    }

    /** [id] alone when given (a command just issued), else every command of [driver]; only the open, unexpired ones. */
    suspend fun deliverable(
        driver: UserId? = null,
        id: CommandId? = null,
    ): List<DeviceCommand> =
        transactions.inTransaction {
            val now = now()
            val candidates = if (id != null) listOfNotNull(commands.find(id)) else commands.openFor(checkNotNull(driver))
            candidates.filter { it.status.isOpen && !it.isDue(now) }
        }

    /** An admin reads [driver]'s newest commands. */
    suspend fun list(
        driver: UserId,
        actor: Actor,
        limit: Int,
    ): List<DeviceCommand> {
        requireAdmin(actor)
        return transactions.inTransaction { commands.latestFor(driver, limit) }
    }

    /** Expires up to [limit] commands that waited too long; returns how many. The expiry job calls it. */
    suspend fun expireDue(limit: Int = EXPIRE_BATCH): Int =
        transactions.inTransaction {
            val now = now()
            commands
                .findDue(now, limit)
                .count { command -> command.expire(now).also { if (it) commands.update(command) } }
        }

    private fun requireAdmin(actor: Actor) {
        if (actor.role != Role.ADMIN) throw DomainException(CommandError.COMMAND_FORBIDDEN, "Only an admin may manage device commands")
    }

    private fun notFound(id: CommandId) = DomainException(CommandError.COMMAND_NOT_FOUND, "No command ${id.value}")

    // Postgres keeps microseconds; see TripService.
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS).toKotlinInstant()

    private companion object {
        const val EXPIRE_BATCH = 500
    }
}

// Time-ordered ids (UUID version 7) keep new rows at the end of the primary key index.
@OptIn(ExperimentalUuidApi::class)
private fun newCommandId() = CommandId(Uuid.generateV7().toJavaUuid())
