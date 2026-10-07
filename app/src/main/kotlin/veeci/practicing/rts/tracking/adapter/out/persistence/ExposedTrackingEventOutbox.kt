package veeci.practicing.rts.tracking.adapter.out.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import veeci.practicing.rts.platform.events.OutboxWriter
import veeci.practicing.rts.tracking.application.port.out.TrackingEventOutbox
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import kotlin.time.Instant

/** Writes tracking's events to the platform outbox, as versioned JSON. */
class ExposedTrackingEventOutbox(
    private val outbox: OutboxWriter,
) : TrackingEventOutbox {
    override suspend fun add(event: FixesAccepted) {
        outbox.append(FIXES_ACCEPTED_V1, Json.encodeToJsonElement(FixesAcceptedPayload.serializer(), event.toPayload()))
    }

    companion object {
        /** The payload format is a contract with the readers of the outbox. A change gets a new version. */
        const val FIXES_ACCEPTED_V1 = "tracking.FixesAccepted.v1"
    }
}

@Serializable
internal data class FixesAcceptedPayload(
    val sessionId: String,
    val deviceId: String,
    val seq: Long,
    val positions: List<PositionPayload>,
)

@Serializable
internal data class PositionPayload(
    val lat: Double,
    val lng: Double,
    val recordedAt: Instant,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val cumulativeM: Double,
)

private fun FixesAccepted.toPayload() =
    FixesAcceptedPayload(
        sessionId = sessionId.toString(),
        deviceId = deviceId,
        seq = seq,
        positions =
            positions.map {
                PositionPayload(
                    it.point.lat,
                    it.point.lng,
                    it.recordedAt,
                    it.speedMps,
                    it.bearingDeg,
                    it.cumulativeM,
                )
            },
    )
