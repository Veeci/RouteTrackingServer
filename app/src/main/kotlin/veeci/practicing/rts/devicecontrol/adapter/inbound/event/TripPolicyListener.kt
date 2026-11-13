package veeci.practicing.rts.devicecontrol.adapter.inbound.event

import kotlinx.serialization.json.JsonElement
import veeci.practicing.rts.devicecontrol.application.CommandPolicies
import veeci.practicing.rts.platform.events.EventJson
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.trip.domain.event.TripStatusChangedV1

/** A trip changed its stage (trip context): geofences at the pickup and destination, a passive setting at the end. */
class TripPolicyListener(
    private val policies: CommandPolicies,
) : OutboxEventHandler {
    override val eventType = TripStatusChangedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        policies.onTripStatus(EventJson.decodeFromJsonElement(TripStatusChangedV1.serializer(), payload).toEvent())
    }
}
