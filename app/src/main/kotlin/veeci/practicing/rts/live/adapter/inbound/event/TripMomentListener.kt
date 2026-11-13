package veeci.practicing.rts.live.adapter.inbound.event

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.JsonElement
import veeci.practicing.rts.live.application.api.LiveUpdates
import veeci.practicing.rts.live.application.api.TripMomentUpdate
import veeci.practicing.rts.platform.events.EventJson
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripMomentRaisedV1

/** The trip context raised a moment, for example "driver arriving": tell the guests who watch the trip. */
class TripMomentListener(
    private val live: LiveUpdates,
    private val metrics: MeterRegistry,
) : OutboxEventHandler {
    override val eventType = TripMomentRaisedV1.TYPE

    override suspend fun handle(payload: JsonElement) {
        // A kind this version does not know (from a newer writer) is skipped.
        val moment = EventJson.decodeFromJsonElement(TripMomentRaisedV1.serializer(), payload).toMoment() ?: return
        // rts_trip_events_total{type}: counted on delivery; a delivery the relay retries counts again.
        metrics.counter("rts.trip.events", "type", moment.kind().lowercase()).increment()
        live.publish(
            TripMomentUpdate(
                moment.tripId,
                moment.kind(),
                moment.status,
                moment.at,
                remainingM = (moment as? TripMoment.DriverArriving)?.remainingM,
                levelPercent = (moment as? TripMoment.DriverLowBattery)?.levelPercent,
                lastSeenAt = (moment as? TripMoment.DriverStale)?.lastSeenAt,
            ),
        )
    }

    private fun TripMoment.kind() =
        when (this) {
            is TripMoment.DriverArriving -> TripMomentRaisedV1.KIND_DRIVER_ARRIVING
            is TripMoment.DriverLowBattery -> TripMomentRaisedV1.KIND_DRIVER_LOW_BATTERY
            is TripMoment.DriverStale -> TripMomentRaisedV1.KIND_DRIVER_STALE
            is TripMoment.DriverBack -> TripMomentRaisedV1.KIND_DRIVER_BACK
            is TripMoment.OffRoute -> TripMomentRaisedV1.KIND_OFF_ROUTE
        }
}
