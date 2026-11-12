package veeci.practicing.rts.devicecontrol.domain

import veeci.practicing.rts.devicecontrol.domain.event.CommandIssued
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import java.util.UUID
import kotlin.time.Instant

@JvmInline
value class CommandId(
    val value: UUID,
)

/** The SDK's accuracy levels. The names match the SDK's enum, so the SDK maps them one to one. */
enum class TrackingAccuracy { HIGH, BALANCED, LOW, PASSIVE }

/** A circle the SDK watches: it wakes its GPS when the phone enters or leaves it. */
data class GeofenceRegion(
    val id: String,
    val center: GeoPoint,
    val radiusM: Double,
)

/** What the device should do. */
sealed interface CommandPayload {
    /** Use at most [maxAccuracy] and send fixes at most every [minIntervalMs]. Only the newest one matters. */
    data class SetConstraint(
        val maxAccuracy: TrackingAccuracy,
        val minIntervalMs: Long,
        val reason: String,
    ) : CommandPayload

    /** Watch these regions. */
    data class RegisterGeofences(
        val regions: List<GeofenceRegion>,
    ) : CommandPayload

    /** Upload every queued batch now. */
    data object FlushQueue : CommandPayload
}

enum class CommandStatus {
    /** Stored; not sent yet (the driver is offline, or on another instance). */
    PENDING,

    /** Sent to the device; no ack yet. Sent again after a reconnect. */
    SENT,
    APPLIED,
    REJECTED,

    /** Not acked in time, or replaced by a newer constraint. Never sent again. */
    EXPIRED,

    ;

    /** Still waiting for the device: it may be (re)sent. */
    val isOpen: Boolean get() = this == PENDING || this == SENT
}

enum class CommandError(
    override val category: ErrorCategory,
) : ErrorCode {
    COMMAND_NOT_FOUND(ErrorCategory.NOT_FOUND),
    COMMAND_FORBIDDEN(ErrorCategory.FORBIDDEN),
    INVALID_COMMAND(ErrorCategory.INVALID),
}

/**
 * A command for one driver's device. It waits until the device acks it, or until it expires.
 *
 * | From          | Event               | To                  |
 * |---------------|---------------------|---------------------|
 * | PENDING       | sent to the device  | SENT                |
 * | PENDING, SENT | ack applied/rejected| APPLIED / REJECTED  |
 * | PENDING, SENT | expired or replaced | EXPIRED             |
 *
 * Anything else changes nothing: a repeated or late ack is ignored, so the device may ack twice.
 */
class DeviceCommand private constructor(
    val id: CommandId,
    val driverId: UserId,
    val payload: CommandPayload,
    val createdAt: Instant,
    val expiresAt: Instant,
    status: CommandStatus,
    updatedAt: Instant,
) {
    var status: CommandStatus = status
        private set

    var updatedAt: Instant = updatedAt
        private set

    private val events = mutableListOf<CommandIssued>()

    fun markSent(now: Instant): Boolean = move(CommandStatus.PENDING, CommandStatus.SENT, now)

    /** False when the command is no longer open (acked before, or expired): the ack is ignored. */
    fun acknowledge(
        applied: Boolean,
        now: Instant,
    ): Boolean {
        if (!status.isOpen) return false
        status = if (applied) CommandStatus.APPLIED else CommandStatus.REJECTED
        updatedAt = now
        return true
    }

    fun expire(now: Instant): Boolean {
        if (!status.isOpen) return false
        status = CommandStatus.EXPIRED
        updatedAt = now
        return true
    }

    fun isDue(now: Instant): Boolean = status.isOpen && now >= expiresAt

    /** The events since the command was issued, each returned once. */
    fun pullEvents(): List<CommandIssued> = events.toList().also { events.clear() }

    private fun move(
        from: CommandStatus,
        to: CommandStatus,
        now: Instant,
    ): Boolean {
        if (status != from) return false
        status = to
        updatedAt = now
        return true
    }

    companion object {
        /** A new command, checked. Throws INVALID_COMMAND for a payload the device could not use. */
        fun issue(
            id: CommandId,
            driverId: UserId,
            payload: CommandPayload,
            now: Instant,
            expiresAt: Instant,
        ): DeviceCommand {
            payload.problem()?.let { throw DomainException(CommandError.INVALID_COMMAND, it) }
            return DeviceCommand(id, driverId, payload, now, expiresAt, CommandStatus.PENDING, now).also {
                it.events += CommandIssued(id.value, driverId.value, payload.kind(), now)
            }
        }

        /** Rebuilds a stored command exactly as it was saved. */
        @Suppress("LongParameterList") // one parameter per stored column
        fun restore(
            id: CommandId,
            driverId: UserId,
            payload: CommandPayload,
            createdAt: Instant,
            expiresAt: Instant,
            status: CommandStatus,
            updatedAt: Instant,
        ) = DeviceCommand(id, driverId, payload, createdAt, expiresAt, status, updatedAt)

        private fun CommandPayload.problem(): String? =
            when (this) {
                is CommandPayload.SetConstraint -> {
                    when {
                        minIntervalMs < 0 -> "minIntervalMs must be 0 or more"
                        reason.isBlank() -> "reason must not be blank"
                        else -> null
                    }
                }

                is CommandPayload.RegisterGeofences -> {
                    when {
                        regions.isEmpty() -> "regions must not be empty"
                        regions.any { it.radiusM <= 0 } -> "every radiusM must be more than 0"
                        regions.any { it.id.isBlank() } -> "every region needs an id"
                        else -> null
                    }
                }

                CommandPayload.FlushQueue -> {
                    null
                }
            }
    }
}

/** The command kind as other contexts and the protocol name it. */
fun CommandPayload.kind(): String =
    when (this) {
        is CommandPayload.SetConstraint -> "SET_CONSTRAINT"
        is CommandPayload.RegisterGeofences -> "REGISTER_GEOFENCES"
        CommandPayload.FlushQueue -> "FLUSH_QUEUE"
    }
