package veeci.practicing.rts.live.adapter.inbound.event

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.JsonElement
import veeci.practicing.rts.live.application.api.LiveUpdates
import veeci.practicing.rts.live.application.api.TripStatusUpdate
import veeci.practicing.rts.platform.events.EventJson
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.trip.domain.event.TripStatusChangedV1

/**
 * A trip changed its stage (trip context): tell the guests who watch it. Status changes come through the outbox,
 * so none is lost; the guest socket reads the trip again on each one, so a repeated event is harmless.
 */
class TripStatusListener(
    private val live: LiveUpdates,
    private val metrics: MeterRegistry,
) : OutboxEventHandler {
    override val eventType = TripStatusChangedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        val event = EventJson.decodeFromJsonElement(TripStatusChangedV1.serializer(), payload).toEvent()
        metrics.counter("rts.trip.events", "type", "status_changed").increment()
        live.publish(TripStatusUpdate(event.tripId, event.to, event.at))
    }
}
