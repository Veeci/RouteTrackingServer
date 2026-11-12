package veeci.practicing.rts.testing.devicecontrol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import veeci.practicing.rts.devicecontrol.application.port.out.CommandEventOutbox
import veeci.practicing.rts.devicecontrol.application.port.out.DeviceCommandRepository
import veeci.practicing.rts.devicecontrol.domain.CommandId
import veeci.practicing.rts.devicecontrol.domain.DeviceCommand
import veeci.practicing.rts.devicecontrol.domain.event.CommandIssued
import veeci.practicing.rts.shared.UserId
import kotlin.time.Instant

/** The command repository in memory; DeviceCommandRepositoryContract runs the same tests against it and Postgres. */
class InMemoryDeviceCommandRepository : DeviceCommandRepository {
    /** A stored copy: changes to a loaded command are lost unless it is updated. */
    private val rows = mutableMapOf<CommandId, DeviceCommand>()
    private val mutex = Mutex()

    override suspend fun insert(command: DeviceCommand) = mutex.withLock { rows[command.id] = command.copy() }

    override suspend fun find(id: CommandId): DeviceCommand? = mutex.withLock { rows[id]?.copy() }

    override suspend fun update(command: DeviceCommand) = mutex.withLock { rows[command.id] = command.copy() }

    override suspend fun openFor(driver: UserId): List<DeviceCommand> =
        mutex.withLock {
            rows.values
                .filter { it.driverId == driver && it.status.isOpen }
                .sortedWith(compareBy({ it.createdAt }, { it.id.value.toString() }))
                .map { it.copy() }
        }

    override suspend fun latestFor(
        driver: UserId,
        limit: Int,
    ): List<DeviceCommand> =
        mutex.withLock {
            rows.values
                .filter { it.driverId == driver }
                .sortedWith(compareByDescending<DeviceCommand> { it.createdAt }.thenByDescending { it.id.value.toString() })
                .take(limit)
                .map { it.copy() }
        }

    override suspend fun findDue(
        now: Instant,
        limit: Int,
    ): List<DeviceCommand> =
        mutex.withLock {
            rows.values
                .filter { it.status.isOpen && it.expiresAt <= now }
                .sortedBy { it.expiresAt }
                .take(limit)
                .map { it.copy() }
        }

    private fun DeviceCommand.copy() = DeviceCommand.restore(id, driverId, payload, createdAt, expiresAt, status, updatedAt)
}

/** Keeps the events, in order, for assertions. */
class InMemoryCommandEventOutbox : CommandEventOutbox {
    val events = mutableListOf<CommandIssued>()

    override suspend fun add(event: CommandIssued) {
        events += event
    }
}
