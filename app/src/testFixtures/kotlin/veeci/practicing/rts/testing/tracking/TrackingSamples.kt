package veeci.practicing.rts.testing.tracking

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Meters
import veeci.practicing.rts.tracking.application.port.out.NewBatch
import veeci.practicing.rts.tracking.domain.AcceptedFix
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.FixReading
import veeci.practicing.rts.tracking.domain.LocationFix
import veeci.practicing.rts.tracking.domain.LocationProvider
import veeci.practicing.rts.tracking.domain.Rejection
import veeci.practicing.rts.tracking.domain.SatelliteHealth
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.TrackingSession
import java.util.UUID
import kotlin.time.Instant

/** Test data for tracking's persistence and integration tests. */
object TrackingSamples {
    /** Milliseconds on purpose: stored times must keep them. */
    val T0: Instant = Instant.parse("2026-10-06T08:00:00.123Z")
    val START = GeoPoint(10.7725, 106.6980)

    fun session(
        id: SessionId = SessionId(UUID.randomUUID()),
        at: Instant = T0,
    ) = TrackingSession(id, DeviceId("phone-1"), sdkVersion = "0.9.0", startedAt = at)

    /**
     * An accepted fix with every value set. The measured values are exact in a 4-byte float (8.5, 11.25, ...),
     * so a round trip through a `real` column returns them unchanged.
     */
    fun fullFix(
        at: Instant,
        index: Int = 0,
        cumulativeM: Double = 0.0,
    ) = AcceptedFix(
        fix =
            LocationFix(
                point = START,
                accuracy = Meters(8.5),
                speedMps = 11.25,
                speedAccuracyMps = 0.5,
                bearingDeg = 87.5,
                bearingAccuracyDeg = 4.0,
                altitudeM = -2.5,
                verticalAccuracyM = 3.5,
                recordedAt = at,
                provider = LocationProvider.AOSP_GPS,
                mock = false,
                satellites = SatelliteHealth(usedInFix = 14, meanCn0DbHz = 31.5),
            ),
        index = index,
        distanceFromPrev = Meters(12.345678),
        cumulative = Meters(cumulativeM),
        derivedSpeedMps = 9.75,
    )

    /** An accepted fix with every optional value absent. */
    fun bareFix(
        at: Instant,
        index: Int = 0,
    ) = AcceptedFix(
        fix =
            LocationFix(
                START,
                Meters(5.0),
                null,
                null,
                null,
                null,
                null,
                null,
                at,
                LocationProvider.UNKNOWN,
                mock = true,
                satellites = null,
            ),
        index = index,
        distanceFromPrev = Meters(0.0),
        cumulative = Meters(0.0),
        derivedSpeedMps = null,
    )

    fun newBatch(
        sessionId: SessionId,
        seq: Long,
        accepted: List<AcceptedFix> = emptyList(),
        rejected: List<Rejection> = emptyList(),
    ) = NewBatch(sessionId, seq, receivedAt = T0, accepted, rejected)

    /** A valid, accurate reading, as the driver socket would hand it to the service. */
    fun reading(at: Instant) =
        FixReading(
            START.lat,
            START.lng,
            5.0,
            null,
            null,
            null,
            null,
            null,
            null,
            at,
            LocationProvider.GMS_FUSED,
            mock = false,
            satellites = null,
        )
}
