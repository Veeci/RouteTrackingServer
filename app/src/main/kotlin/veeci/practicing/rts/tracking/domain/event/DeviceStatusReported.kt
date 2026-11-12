package veeci.practicing.rts.tracking.domain.event

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Instant

/**
 * A driver's device reported its battery and motion (phase 4). Other contexts react to it (trip: low battery;
 * telemetry later), so it uses only plain types, like [FixesAccepted].
 */
data class DeviceStatusReported(
    val sessionId: UUID,
    val deviceId: String,
    /** The user id of the driver; null for sessions stored before drivers had accounts. */
    val driverId: UUID?,
    /** When the device read the values. */
    val at: Instant,
    val battery: Battery,
    val motion: String?,
) {
    data class Battery(
        val levelPercent: Int,
        val isCharging: Boolean,
        val temperatureC: Double?,
        val profile: String?,
    )
}

/**
 * [DeviceStatusReported] as JSON in the outbox, under the event type [TYPE]. A contract with every reader: fields
 * may be added, but a change to an existing field needs a new type, `...v2`.
 */
@Serializable
data class DeviceStatusReportedV1(
    val sessionId: String,
    val deviceId: String,
    val driverId: String? = null,
    val at: Instant,
    val levelPercent: Int,
    val isCharging: Boolean,
    val temperatureC: Double? = null,
    val profile: String? = null,
    val motion: String? = null,
) {
    fun toEvent() =
        DeviceStatusReported(
            UUID.fromString(sessionId),
            deviceId,
            driverId?.let(UUID::fromString),
            at,
            DeviceStatusReported.Battery(levelPercent, isCharging, temperatureC, profile),
            motion,
        )

    companion object {
        const val TYPE = "tracking.DeviceStatusReported.v1"

        fun of(event: DeviceStatusReported) =
            DeviceStatusReportedV1(
                event.sessionId.toString(),
                event.deviceId,
                event.driverId?.toString(),
                event.at,
                event.battery.levelPercent,
                event.battery.isCharging,
                event.battery.temperatureC,
                event.battery.profile,
                event.motion,
            )
    }
}
