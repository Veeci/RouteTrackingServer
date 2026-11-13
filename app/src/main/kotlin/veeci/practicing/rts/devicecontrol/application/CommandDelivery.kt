package veeci.practicing.rts.devicecontrol.application

import veeci.practicing.rts.devicecontrol.domain.CommandId
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.devicecontrol.domain.DeviceCommand
import veeci.practicing.rts.devicecontrol.domain.kind
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.protocol.GeofenceDto
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.DriverLink

/**
 * Gets commands to the driver's device over tracking's [DriverLink]: at once when the driver is connected to
 * this instance, else right after their next `welcome`. Delivery is at least once: a SENT command without an
 * ack is sent again after every reconnect, and the device handles each `commandId` once.
 */
class CommandDelivery(
    private val link: DriverLink,
    private val commands: CommandService,
) {
    /** Registers with the driver sockets. Called once at startup. */
    fun start() {
        link.onConnected { driver -> deliverPending(driver) }
        link.onCommandAck { driver, id, applied -> commands.acknowledge(CommandId(id), driver, applied) }
    }

    /** A command was just issued: send it if its driver is connected here. */
    suspend fun deliver(id: CommandId) {
        for (command in commands.deliverable(id = id)) {
            if (link.send(command.driverId, command.toMessage())) commands.markSent(command.id)
        }
    }

    /** The driver just connected: send everything that waits for them, oldest first. */
    suspend fun deliverPending(driver: UserId) {
        for (command in commands.deliverable(driver = driver)) {
            if (link.send(driver, command.toMessage())) commands.markSent(command.id)
        }
    }

    private fun DeviceCommand.toMessage(): Command {
        val payload = payload
        return Command(
            commandId = id.value.toString(),
            kind = payload.kind(),
            maxAccuracy = (payload as? CommandPayload.SetConstraint)?.maxAccuracy?.name,
            minIntervalMs = (payload as? CommandPayload.SetConstraint)?.minIntervalMs,
            reason = (payload as? CommandPayload.SetConstraint)?.reason,
            regions =
                (payload as? CommandPayload.RegisterGeofences)?.regions?.map {
                    GeofenceDto(it.id, it.center.lat, it.center.lng, it.radiusM)
                },
        )
    }
}
