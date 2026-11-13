package veeci.practicing.rts.devicecontrol.adapter.inbound.event

import kotlinx.serialization.json.JsonElement
import veeci.practicing.rts.devicecontrol.application.CommandDelivery
import veeci.practicing.rts.devicecontrol.domain.CommandId
import veeci.practicing.rts.devicecontrol.domain.event.CommandIssuedV1
import veeci.practicing.rts.platform.events.EventJson
import veeci.practicing.rts.platform.events.OutboxEventHandler

/**
 * A command was stored: send it now if its driver is connected here. Runs after the command's transaction
 * committed (through the outbox), so the device never gets a command that was rolled back.
 */
class CommandIssuedListener(
    private val delivery: CommandDelivery,
) : OutboxEventHandler {
    override val eventType = CommandIssuedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        val event = EventJson.decodeFromJsonElement(CommandIssuedV1.serializer(), payload).toEvent()
        delivery.deliver(CommandId(event.commandId))
    }
}
