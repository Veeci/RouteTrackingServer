package veeci.practicing.rts.trip.adapter.inbound.event

import kotlinx.serialization.json.JsonElement
import veeci.practicing.rts.platform.events.EventJson
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReportedV1
import veeci.practicing.rts.tracking.domain.event.FixesAcceptedV1
import veeci.practicing.rts.trip.application.TripEventService
import veeci.practicing.rts.trip.application.TripProgressService
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripStatusChangedV1

/** The driver moved (tracking context): update the progress of their active trip. */
class FixesAcceptedListener(
    private val progress: TripProgressService,
) : OutboxEventHandler {
    override val eventType = FixesAcceptedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        progress.onFixesAccepted(EventJson.decodeFromJsonElement(FixesAcceptedV1.serializer(), payload).toEvent())
    }
}

/** A trip finished: forget its progress state. */
class TripFinishedListener(
    private val progress: TripProgressService,
) : OutboxEventHandler {
    override val eventType = TripStatusChangedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        val event = EventJson.decodeFromJsonElement(TripStatusChangedV1.serializer(), payload).toEvent()
        if (event.to.isTerminal) progress.forget(TripId(event.tripId))
    }
}

/** The driver moved (tracking context): check whether they are arriving at, or have arrived at, the pickup. */
class ArrivalListener(
    private val events: TripEventService,
) : OutboxEventHandler {
    override val eventType = FixesAcceptedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        events.onFixesAccepted(EventJson.decodeFromJsonElement(FixesAcceptedV1.serializer(), payload).toEvent())
    }
}

/** The driver's device reported its status (tracking context): check the battery. */
class BatteryListener(
    private val events: TripEventService,
) : OutboxEventHandler {
    override val eventType = DeviceStatusReportedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        events.onDeviceStatus(EventJson.decodeFromJsonElement(DeviceStatusReportedV1.serializer(), payload).toEvent())
    }
}
