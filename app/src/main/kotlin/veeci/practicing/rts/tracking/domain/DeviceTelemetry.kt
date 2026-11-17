package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.tracking.domain.event.DeviceStatusReported
import kotlin.time.Instant

/**
 * The phone a session runs on, from the `device` block of hello (phase 9). Every field is optional: an older SDK
 * sends no block, and a ROM may hide some values. Session health groups its report by these values.
 */
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val androidSdk: Int? = null,
    val rom: String? = null,
    val hasGms: Boolean? = null,
)

/** One device_status message, as stored for session health (phase 9). [at] is when the device read the values. */
data class DeviceStatusSample(
    val sessionId: SessionId,
    val at: Instant,
    val battery: DeviceStatusReported.Battery,
    val motion: String?,
)
