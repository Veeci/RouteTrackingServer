package veeci.practicing.rts.fleet.adapter.inbound.event

import kotlinx.serialization.json.JsonElement
import veeci.practicing.rts.fleet.application.FleetProjection
import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.identity.domain.event.DriverAccountChangedV1
import veeci.practicing.rts.platform.events.EventJson
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReportedV1
import veeci.practicing.rts.tracking.domain.event.FixesAcceptedV1
import veeci.practicing.rts.trip.domain.event.TripStatusChangedV1

// The fleet read model's event handlers (phase 11). They run inside the relay's transaction, so the row changes
// commit with the event's delivery. Each one is safe to run twice: the projection applies only newer data.

/** The driver moved (tracking): their newest position is the batch's last fix. */
class FleetPositionListener(
    private val projection: FleetProjection,
) : OutboxEventHandler {
    override val eventType = FixesAcceptedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        val event = EventJson.decodeFromJsonElement(FixesAcceptedV1.serializer(), payload).toEvent()
        val driver = event.driverId ?: return // a session from before drivers had accounts
        val last = event.positions.lastOrNull() ?: return
        projection.onPosition(driver, DriverPosition(last.point.lat, last.point.lng, last.speedMps, last.bearingDeg, last.recordedAt))
    }
}

/** The driver's phone reported its battery and motion (tracking). */
class FleetDeviceStatusListener(
    private val projection: FleetProjection,
) : OutboxEventHandler {
    override val eventType = DeviceStatusReportedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        val event = EventJson.decodeFromJsonElement(DeviceStatusReportedV1.serializer(), payload).toEvent()
        val driver = event.driverId ?: return
        projection.onDeviceStatus(driver, DeviceState(event.battery.levelPercent, event.battery.isCharging, event.motion, event.at))
    }
}

/** A trip changed stage (trip). A trip with no driver yet (requested, or cancelled before acceptance) is not a driver's. */
class FleetTripListener(
    private val projection: FleetProjection,
) : OutboxEventHandler {
    override val eventType = TripStatusChangedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        val event = EventJson.decodeFromJsonElement(TripStatusChangedV1.serializer(), payload).toEvent()
        val driver = event.driverId ?: return
        projection.onTripStage(driver, event.tripId, event.to.name, event.to.isTerminal, event.at)
    }
}

/** A driver account was created, renamed, disabled or enabled (identity). */
class FleetAccountListener(
    private val projection: FleetProjection,
) : OutboxEventHandler {
    override val eventType = DriverAccountChangedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        val event = EventJson.decodeFromJsonElement(DriverAccountChangedV1.serializer(), payload).toEvent()
        projection.onAccount(event.driverId, event.displayName, event.enabled)
    }
}
