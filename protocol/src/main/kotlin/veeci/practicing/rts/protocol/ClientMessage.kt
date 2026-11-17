package veeci.practicing.rts.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("hello")
data class Hello(
    val protocolVersion: Int,
    val deviceId: String,
    val sessionId: String,
    val lastAckedSeq: Long? = null,
    val sdkVersion: String,
    /** What the phone is (phase 9). Optional: an SDK older than phase 9 sends no block, and the server stores none. */
    val device: DeviceDto? = null,
) : ClientMessage

/**
 * The phone a session runs on, for comparing location stacks across devices and ROMs (phase 9). Every field is
 * optional: an SDK sends what it can read.
 */
@Serializable
data class DeviceDto(
    /** `Build.MANUFACTURER`, for example Google. */
    val manufacturer: String? = null,
    /** `Build.MODEL`, for example Pixel 7. */
    val model: String? = null,
    /** `Build.VERSION.SDK_INT`, the Android API level, for example 35. */
    val androidSdk: Int? = null,
    /** The ROM's name and version when it is not the stock one, for example LineageOS 22.1. */
    val rom: String? = null,
    /** Whether Google Play services is installed and usable. */
    val hasGms: Boolean? = null,
)

/**
 * The device's state, sent every 30 s while tracking runs, also when the driver is parked and sends no batches:
 * it is the driver's heartbeat (a driver silent for 60 s counts as gone). No ack: the next one follows anyway.
 * Before `hello` it closes the socket with 4401, like a batch.
 */
@Serializable
@SerialName("device_status")
data class DeviceStatus(
    /** When the device read these values. */
    val at: Instant,
    val battery: BatteryDto,
    /** The activity the device detected, for example STILL, WALKING or IN_VEHICLE; null when unknown. */
    val motion: String? = null,
) : ClientMessage

@Serializable
data class BatteryDto(
    /** 0 to 100. Another value gets `error{INVALID_MESSAGE}` and the session stays open. */
    val levelPercent: Int,
    val isCharging: Boolean,
    val temperatureC: Double? = null,
    /** The SDK's battery profile name, for example CRITICAL. A server treats a name it does not know as normal. */
    val profile: String? = null,
)

/**
 * The device's answer to a `command`: [result] is applied or rejected. Send it once the command was carried out
 * (or refused). The server may send a command again after a reconnect until it gets this ack, so the device
 * treats a `commandId` it already handled as done and acks it again.
 */
@Serializable
@SerialName("command_ack")
data class CommandAck(
    val commandId: String,
    val result: String,
    /** Why a command was rejected; optional. */
    val reason: String? = null,
) : ClientMessage {
    companion object {
        const val APPLIED = "applied"
        const val REJECTED = "rejected"
    }
}

@Serializable
@SerialName("fix_batch")
data class FixBatch(
    val seq: Long,
    val fixes: List<FixDto>,
) : ClientMessage
