package veeci.practicing.rts.devicecontrol.adapter.out.persistence

import kotlinx.serialization.json.Json
import veeci.practicing.rts.devicecontrol.application.port.out.CommandEventOutbox
import veeci.practicing.rts.devicecontrol.domain.event.CommandIssued
import veeci.practicing.rts.devicecontrol.domain.event.CommandIssuedV1
import veeci.practicing.rts.platform.events.OutboxWriter

/** Writes device control's events to the platform outbox, as versioned JSON. */
class ExposedCommandEventOutbox(
    private val outbox: OutboxWriter,
) : CommandEventOutbox {
    override suspend fun add(event: CommandIssued) {
        outbox.append(CommandIssuedV1.TYPE, Json.encodeToJsonElement(CommandIssuedV1.serializer(), CommandIssuedV1.of(event)))
    }
}
