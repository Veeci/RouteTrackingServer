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
    device: DeviceInfo? = null,
) {
    var sdkVersion: String = sdkVersion
        private set

    var lastSeenAt: Instant = lastSeenAt
        private set

    /** The phone, from the latest hello that carried a device block (phase 9). */
    var device: DeviceInfo? = device
        private set

    fun reconnect(
        deviceId: DeviceId,
        driverId: UserId,
        sdkVersion: String,
        at: Instant,
        device: DeviceInfo? = null,
    ) {
        if (deviceId != this.deviceId || driverId != this.driverId) {
            throw DomainException(
                TrackingError.SESSION_DEVICE_MISMATCH,
                "This session belongs to another device or driver",
            )
        }
        this.sdkVersion = sdkVersion
        // A hello without the block keeps what is stored: an SDK downgrade does not erase the device.
        if (device != null) this.device = device
        touch(at)
    }

    fun touch(at: Instant) {
        if (at > lastSeenAt) lastSeenAt = at
    }

    fun resumeFrom(highestStoredSeq: Long?): Long = (highestStoredSeq ?: 0) + 1
}
