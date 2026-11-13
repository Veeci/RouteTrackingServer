package veeci.practicing.rts.live.adapter.inbound.event

import kotlinx.serialization.json.JsonElement
import veeci.practicing.rts.live.application.api.LiveUpdates
import veeci.practicing.rts.live.application.api.RouteUpdate
import veeci.practicing.rts.platform.events.EventJson
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.trip.domain.event.TripRouteChangedV1
import java.util.UUID

/** The trip context stored a new route (a re-route): tell the guests who watch the trip. */
class TripRouteListener(
    private val live: LiveUpdates,
) : OutboxEventHandler {
    override val eventType = TripRouteChangedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        val change = EventJson.decodeFromJsonElement(TripRouteChangedV1.serializer(), payload)
        live.publish(
            RouteUpdate(
                UUID.fromString(change.tripId),
                change.kind,
                change.version,
                change.polyline,
                change.distanceM,
                change.durationS,
                change.reason,
            ),
        )
    }
}
