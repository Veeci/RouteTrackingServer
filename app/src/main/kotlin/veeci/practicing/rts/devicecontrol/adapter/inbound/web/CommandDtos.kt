package veeci.practicing.rts.devicecontrol.adapter.inbound.web

import kotlinx.serialization.Serializable
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.devicecontrol.domain.DeviceCommand
import veeci.practicing.rts.devicecontrol.domain.GeofenceRegion
import veeci.practicing.rts.devicecontrol.domain.TrackingAccuracy
import veeci.practicing.rts.devicecontrol.domain.kind
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.Validatable
import veeci.practicing.rts.shared.geo.GeoPoint
import kotlin.time.Instant

// The JSON shapes of the admin command API (contract in openapi/rts-v1.yaml). One flat shape for every kind:
// `kind` says which fields apply, which keeps validation and the contract simple.

@Serializable
data class GeofenceDto(
    val id: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
)

@Serializable
data class IssueCommandBody(
    /** SET_CONSTRAINT, REGISTER_GEOFENCES or FLUSH_QUEUE. */
    val kind: String,
    val maxAccuracy: String? = null,
    val minIntervalMs: Long? = null,
    val reason: String? = null,
    val regions: List<GeofenceDto>? = null,
) : Validatable {
    override fun validate(): List<FieldError> =
        buildList {
            when (kind) {
                SET_CONSTRAINT -> {
                    if (maxAccuracy == null || TrackingAccuracy.entries.none { it.name == maxAccuracy }) {
                        add(FieldError("maxAccuracy", "must be one of ${TrackingAccuracy.entries.joinToString()}"))
                    }
                    if (minIntervalMs == null || minIntervalMs < 0) add(FieldError("minIntervalMs", "must be 0 or more"))
                    if (reason.isNullOrBlank()) add(FieldError("reason", "must not be blank"))
                }

                REGISTER_GEOFENCES -> {
                    if (regions.isNullOrEmpty()) add(FieldError("regions", "must not be empty"))
                    regions.orEmpty().forEachIndexed { i, region -> addAll(region.errors("regions[$i]")) }
                }

                FLUSH_QUEUE -> {}

                else -> {
                    add(FieldError("kind", "must be one of $SET_CONSTRAINT, $REGISTER_GEOFENCES, $FLUSH_QUEUE"))
                }
            }
        }

    /** Only after [validate] found nothing. */
    fun toPayload(): CommandPayload =
        when (kind) {
            SET_CONSTRAINT -> {
                CommandPayload.SetConstraint(TrackingAccuracy.valueOf(maxAccuracy!!), minIntervalMs!!, reason!!)
            }

            REGISTER_GEOFENCES -> {
                CommandPayload.RegisterGeofences(
                    regions!!.map { GeofenceRegion(it.id, GeoPoint(it.lat, it.lng), it.radiusM) },
                )
            }

            else -> {
                CommandPayload.FlushQueue
            }
        }

    private fun GeofenceDto.errors(field: String) =
        buildList {
            if (id.isBlank()) add(FieldError("$field.id", "must not be blank"))
            if (lat !in GeoPoint.LATITUDES) add(FieldError("$field.lat", "must be between -90 and 90"))
            if (lng !in GeoPoint.LONGITUDES) add(FieldError("$field.lng", "must be between -180 and 180"))
            if (radiusM <= 0) add(FieldError("$field.radiusM", "must be more than 0"))
        }
}

// Not in a companion object: @Serializable puts serializer() there, and a private companion would hide it from Ktor.
private const val SET_CONSTRAINT = "SET_CONSTRAINT"
private const val REGISTER_GEOFENCES = "REGISTER_GEOFENCES"
private const val FLUSH_QUEUE = "FLUSH_QUEUE"

@Serializable
data class IssuedCommandResponse(
    val commandId: String,
)

@Serializable
data class CommandResponse(
    val commandId: String,
    val driverId: String,
    val kind: String,
    val status: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val updatedAt: Instant,
    val maxAccuracy: String? = null,
    val minIntervalMs: Long? = null,
    val reason: String? = null,
    val regions: List<GeofenceDto>? = null,
) {
    companion object {
        fun of(command: DeviceCommand): CommandResponse {
            val payload = command.payload
            return CommandResponse(
                commandId = command.id.value.toString(),
                driverId = command.driverId.value.toString(),
                kind = payload.kind(),
                status = command.status.name,
                createdAt = command.createdAt,
                expiresAt = command.expiresAt,
                updatedAt = command.updatedAt,
                maxAccuracy = (payload as? CommandPayload.SetConstraint)?.maxAccuracy?.name,
                minIntervalMs = (payload as? CommandPayload.SetConstraint)?.minIntervalMs,
                reason = (payload as? CommandPayload.SetConstraint)?.reason,
                regions =
                    (payload as? CommandPayload.RegisterGeofences)?.regions?.map {
                        GeofenceDto(it.id, it.center.lat, it.center.lng, it.radiusM)
                    },
            )
        }
    }
}

@Serializable
data class CommandListResponse(
    val items: List<CommandResponse>,
)
