package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.UserId
import kotlin.time.Instant

class TrackingSession(
    val id: SessionId,
    val deviceId: DeviceId,
    sdkVersion: String,
    val startedAt: Instant,
    lastSeenAt: Instant = startedAt,
    /** The user who opened the session. Null only for sessions stored before drivers had accounts. */
    val driverId: UserId? = null,
) {
    var sdkVersion: String = sdkVersion
        private set

    var lastSeenAt: Instant = lastSeenAt
        private set

    fun reconnect(
        deviceId: DeviceId,
        driverId: UserId,
        sdkVersion: String,
        at: Instant,
    ) {
        if (deviceId != this.deviceId || driverId != this.driverId) {
            throw DomainException(
                TrackingError.SESSION_DEVICE_MISMATCH,
                "This session belongs to another device or driver",
            )
        }
        this.sdkVersion = sdkVersion
        touch(at)
    }

    fun touch(at: Instant) {
        if (at > lastSeenAt) lastSeenAt = at
    }

    fun resumeFrom(highestStoredSeq: Long?): Long = (highestStoredSeq ?: 0) + 1
}
