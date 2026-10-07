package veeci.practicing.rts.trip.adapter.inbound.web

import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.Validatable
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.application.RequestTrip
import veeci.practicing.rts.trip.application.api.TripSnapshot
import veeci.practicing.rts.trip.application.port.out.TripCursor
import veeci.practicing.rts.trip.domain.TripId
import java.util.Base64
import java.util.UUID
import kotlin.time.Instant

// The JSON shapes of the trip API. They are separate from the domain classes on purpose: the domain can change
// without changing what clients receive, and the contract (openapi/rts-v1.yaml) describes exactly these classes.

@Serializable
data class PointDto(
    val lat: Double,
    val lng: Double,
) {
    fun toGeoPoint() = GeoPoint(lat, lng)

    companion object {
        fun of(point: GeoPoint) = PointDto(point.lat, point.lng)
    }
}

@Serializable
data class RequestTripBody(
    val pickup: PointDto,
    val destination: PointDto,
) : Validatable {
    override fun validate(): List<FieldError> =
        buildList {
            addAll(pickup.errors("pickup"))
            addAll(destination.errors("destination"))
            if (pickup == destination) add(FieldError("destination", "must differ from pickup"))
        }

    fun toCommand() = RequestTrip(pickup.toGeoPoint(), destination.toGeoPoint())

    private fun PointDto.errors(field: String) =
        buildList {
            if (lat !in GeoPoint.LATITUDES) add(FieldError("$field.lat", "must be between -90 and 90"))
            if (lng !in GeoPoint.LONGITUDES) add(FieldError("$field.lng", "must be between -180 and 180"))
        }
}

@Serializable
data class TripResponse(
    val id: String,
    val guestId: String,
    val driverId: String? = null,
    val pickup: PointDto,
    val destination: PointDto,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Also sent as the ETag header. */
    val version: Long,
) {
    companion object {
        fun of(trip: TripSnapshot) =
            TripResponse(
                id = trip.id.toString(),
                guestId = trip.guestId.toString(),
                driverId = trip.driverId?.toString(),
                pickup = PointDto.of(trip.pickup),
                destination = PointDto.of(trip.destination),
                status = trip.status.name,
                createdAt = trip.createdAt,
                updatedAt = trip.updatedAt,
                version = trip.version,
            )
    }
}

@Serializable
data class TripPageResponse(
    val items: List<TripResponse>,
    /** Pass it as `cursor` to get the next page; absent on the last page. */
    val nextCursor: String? = null,
)

/** Page cursors are opaque to clients: Base64URL of `<createdAt>|<id>`. */
object TripCursors {
    private const val SEPARATOR = "|"

    fun encode(cursor: TripCursor): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${cursor.createdAt}$SEPARATOR${cursor.id.value}".toByteArray())

    fun decode(text: String): TripCursor? =
        runCatching {
            val (createdAt, id) = String(Base64.getUrlDecoder().decode(text)).split(SEPARATOR, limit = 2)
            TripCursor(Instant.parse(createdAt), TripId(UUID.fromString(id)))
        }.getOrNull()
}
