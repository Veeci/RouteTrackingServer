package veeci.practicing.rts.trip.adapter.out.persistence

import kotlinx.serialization.json.Json
import veeci.practicing.rts.platform.events.OutboxWriter
import veeci.practicing.rts.trip.application.port.out.TripEventOutbox
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripMomentRaisedV1
import veeci.practicing.rts.trip.domain.event.TripStatusChanged
import veeci.practicing.rts.trip.domain.event.TripStatusChangedV1

/** Writes the trip context's events to the platform outbox, as versioned JSON. */
class ExposedTripEventOutbox(
    private val outbox: OutboxWriter,
) : TripEventOutbox {
    override suspend fun add(event: TripStatusChanged) {
        outbox.append(TripStatusChangedV1.TYPE, Json.encodeToJsonElement(TripStatusChangedV1.serializer(), TripStatusChangedV1.of(event)))
    }

    override suspend fun add(moment: TripMoment) {
        outbox.append(TripMomentRaisedV1.TYPE, Json.encodeToJsonElement(TripMomentRaisedV1.serializer(), TripMomentRaisedV1.of(moment)))
    }
}
