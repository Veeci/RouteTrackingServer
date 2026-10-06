package veeci.practicing.rts.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

@Serializable
data class FixDto(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val speedMps: Double? = null,
    val speedAccuracyMps: Double? = null,
    val bearingDeg: Double? = null,
    val bearingAccuracyDeg: Double? = null,
    val altitudeM: Double? = null,
    val verticalAccuracyM: Double? = null,
    /** Device clock, UTC. */
    val recordedAt: Instant,
    val provider: LocationProviderDto = LocationProviderDto.UNKNOWN,
    val mock: Boolean = false,
    /** Absent for fixes that did not come from GNSS (Wi-Fi, cell). */
    val satellites: SatellitesDto? = null,
)

@Serializable
data class SatellitesDto(
    val usedInFix: Int,
    val meanCn0DbHz: Double? = null,
)

@Serializable(with = LocationProviderDtoSerializer::class)
enum class LocationProviderDto { GMS_FUSED, AOSP_GPS, AOSP_NETWORK, AOSP_FUSED, UNKNOWN, }

internal object LocationProviderDtoSerializer : KSerializer<LocationProviderDto> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "veeci.practicing.rts.protocol.LocationProviderDto",
            PrimitiveKind.STRING,
        )

    override fun serialize(
        encoder: Encoder,
        value: LocationProviderDto,
    ) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): LocationProviderDto {
        val name = decoder.decodeString()
        return LocationProviderDto.entries.firstOrNull { it.name == name } ?: LocationProviderDto.UNKNOWN
    }
}
