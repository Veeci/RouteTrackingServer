package veeci.practicing.rts.tracking.adapter.out.persistence

import kotlinx.serialization.json.Json
import veeci.practicing.rts.platform.events.OutboxWriter
import veeci.practicing.rts.tracking.application.port.out.TrackingEventOutbox
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.tracking.domain.event.FixesAcceptedV1

/** Writes tracking's events to the platform outbox, as versioned JSON. */
class ExposedTrackingEventOutbox(
    private val outbox: OutboxWriter,
) : TrackingEventOutbox {
    override suspend fun add(event: FixesAccepted) {
        outbox.append(FixesAcceptedV1.TYPE, Json.encodeToJsonElement(FixesAcceptedV1.serializer(), FixesAcceptedV1.of(event)))
    }
}
