package veeci.practicing.rts.tracking.adapter.inbound.ws

import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.protocol.Hello
import veeci.practicing.rts.tracking.application.IngestBatch
import veeci.practicing.rts.tracking.application.OpenSession
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.FixReading
import veeci.practicing.rts.tracking.domain.LocationProvider
import veeci.practicing.rts.tracking.domain.SatelliteHealth
import veeci.practicing.rts.tracking.domain.SessionId
import java.util.UUID

/** Turns wire messages into service commands. The only place where protocol types meet tracking types. */
internal object DriverMessageMapper {
    /** Null if the ids in [hello] are not valid; the caller answers INVALID_MESSAGE. */
    fun openSession(hello: Hello): OpenSession? {
        val sessionId = parseUuid(hello.sessionId)
        return if (sessionId != null && DeviceId.isValid(hello.deviceId)) {
            OpenSession(SessionId(sessionId), DeviceId(hello.deviceId), hello.sdkVersion)
        } else {
            null
        }
    }

    fun ingest(
        sessionId: SessionId,
        batch: FixBatch,
    ) = IngestBatch(sessionId, batch.seq, batch.fixes.map { it.toReading() })

    private fun FixDto.toReading() =
        FixReading(
            lat = lat,
            lng = lng,
            accuracyM = accuracyM,
            speedMps = speedMps,
            speedAccuracyMps = speedAccuracyMps,
            bearingDeg = bearingDeg,
            bearingAccuracyDeg = bearingAccuracyDeg,
            altitudeM = altitudeM,
            verticalAccuracyM = verticalAccuracyM,
            recordedAt = recordedAt,
            provider = LocationProvider.valueOf(provider.name),
            mock = mock,
            satellites = satellites?.let { SatelliteHealth(it.usedInFix, it.meanCn0DbHz) },
        )

    private fun parseUuid(text: String): UUID? =
        try {
            UUID.fromString(text)
        } catch (_: IllegalArgumentException) {
            null
        }
}
