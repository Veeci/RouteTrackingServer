package veeci.practicing.rts.devicecontrol.application.port.out

import veeci.practicing.rts.devicecontrol.domain.CommandId
import veeci.practicing.rts.devicecontrol.domain.DeviceCommand
import veeci.practicing.rts.devicecontrol.domain.event.CommandIssued
import veeci.practicing.rts.shared.UserId
import kotlin.time.Instant

/** Joins the caller's transaction. */
interface DeviceCommandRepository {
    suspend fun insert(command: DeviceCommand)

    suspend fun find(id: CommandId): DeviceCommand?

    /** Saves the status and the update time. */
    suspend fun update(command: DeviceCommand)

    /** The driver's PENDING and SENT commands, oldest first. */
    suspend fun openFor(driver: UserId): List<DeviceCommand>

    /** The driver's newest commands, newest first. */
    suspend fun latestFor(
        driver: UserId,
        limit: Int,
    ): List<DeviceCommand>

    /** Up to [limit] PENDING or SENT commands whose expiry time is at or before [now]. */
    suspend fun findDue(
        now: Instant,
        limit: Int,
    ): List<DeviceCommand>
}

/** Stores device control's events for delivery after the commit. Joins the caller's transaction. */
interface CommandEventOutbox {
    suspend fun add(event: CommandIssued)
}
