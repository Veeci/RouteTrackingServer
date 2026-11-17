package veeci.practicing.rts.identity.adapter.out.persistence

import kotlinx.serialization.json.Json
import veeci.practicing.rts.identity.application.port.out.IdentityEventOutbox
import veeci.practicing.rts.identity.domain.event.DriverAccountChanged
import veeci.practicing.rts.identity.domain.event.DriverAccountChangedV1
import veeci.practicing.rts.platform.events.OutboxWriter

/** Writes the identity context's events to the platform outbox, as versioned JSON. */
class ExposedIdentityEventOutbox(
    private val outbox: OutboxWriter,
) : IdentityEventOutbox {
    override suspend fun add(event: DriverAccountChanged) {
        outbox.append(
            DriverAccountChangedV1.TYPE,
            Json.encodeToJsonElement(DriverAccountChangedV1.serializer(), DriverAccountChangedV1.of(event)),
        )
    }
}
