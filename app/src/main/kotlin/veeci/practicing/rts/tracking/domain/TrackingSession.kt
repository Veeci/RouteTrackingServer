package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.DomainException
import kotlin.time.Instant

class TrackingSession(
    val id: SessionId,
    val deviceId: DeviceId,
    sdkVersion: String,
    val startedAt: Instant,
    lastSeenAt: Instant = startedAt,
) {
    var sdkVersion: String = sdkVersion
        private set

    var lastSeenAt: Instant = lastSeenAt
        private set

    fun reconnect(
        deviceId: DeviceId,
        sdkVersion: String,
        at: Instant,
    ) {
        if (deviceId != this.deviceId) {
            throw DomainException(
                TrackingError.SESSION_DEVICE_MISMATCH,
                "This session belongs to another device",
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
