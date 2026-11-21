package veeci.practicing.rts.trip.adapter.inbound.event

import kotlinx.serialization.json.JsonElement
import veeci.practicing.rts.platform.events.EventJson
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReportedV1
import veeci.practicing.rts.tracking.domain.event.FixesAcceptedV1
import veeci.practicing.rts.trip.adapter.out.driver.DriverCancelPush
import veeci.practicing.rts.trip.adapter.out.driver.DriverRoutePush
import veeci.practicing.rts.trip.application.DriverMovementService
import veeci.practicing.rts.trip.application.TripEventService
import veeci.practicing.rts.trip.application.TripProgressService
import veeci.practicing.rts.trip.application.TripSummaryService
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripRouteChangedV1
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.event.TripStatusChangedV1

/**
 * The driver moved (tracking context): check arrival at the pickup, then update the progress of their active trip.
 * One handler, so the active trip is read once per event (fleet lag fix A1).
 */
class FixesAcceptedListener(
    private val movement: DriverMovementService,
) : OutboxEventHandler {
    override val eventType = FixesAcceptedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        movement.onFixesAccepted(EventJson.decodeFromJsonElement(FixesAcceptedV1.serializer(), payload).toEvent())
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

/** The driver's device reported its status (tracking context): check the battery. */
class BatteryListener(
    private val events: TripEventService,
) : OutboxEventHandler {
    override val eventType = DeviceStatusReportedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        events.onDeviceStatus(EventJson.decodeFromJsonElement(DeviceStatusReportedV1.serializer(), payload).toEvent())
    }
}

/** The trip's route changed: send it to the driver's device (phase 7c). */
class DriverRouteListener(
    private val push: DriverRoutePush,
) : OutboxEventHandler {
    override val eventType = TripRouteChangedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        push.push(EventJson.decodeFromJsonElement(TripRouteChangedV1.serializer(), payload))
    }
}

/** A trip was cancelled: tell its driver, with who cancelled it (phase 11d). */
class DriverCancelListener(
    private val push: DriverCancelPush,
) : OutboxEventHandler {
    override val eventType = TripStatusChangedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        push.push(EventJson.decodeFromJsonElement(TripStatusChangedV1.serializer(), payload).toEvent())
    }
}

/** A trip completed: compute and store its summary (phase 8a). */
class TripSummaryListener(
    private val summaries: TripSummaryService,
) : OutboxEventHandler {
    override val eventType = TripStatusChangedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        val event = EventJson.decodeFromJsonElement(TripStatusChangedV1.serializer(), payload).toEvent()
        if (event.to == TripStatus.COMPLETED) summaries.summarize(TripId(event.tripId))
    }
}
