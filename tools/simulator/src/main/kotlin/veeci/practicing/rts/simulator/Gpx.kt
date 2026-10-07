package veeci.practicing.rts.simulator

import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.protocol.LocationProviderDto
import java.io.File
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.time.Instant

data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val time: Instant,
    val desc: String?,
)

object GpxReader {
    fun load(location: String): List<TrackPoint> {
        val file = File(location)
        val stream =
            if (file.isFile) {
                file.inputStream()
            } else {
                GpxReader::class.java.classLoader.getResourceAsStream(location)
                    ?: throw IllegalArgumentException("No GPX file or resource at '$location'")
            }

        return stream.use(::read)
    }

    fun read(input: InputStream): List<TrackPoint> {
        val factory =
            XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }
        val xml = factory.createXMLStreamReader(input)

        try {
            return buildList {
                while (xml.hasNext()) {
                    if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.localName == "trkpt") add(xml.readPoint())
                }
            }
        } finally {
            xml.close()
        }
    }

    private fun XMLStreamReader.readPoint(): TrackPoint {
        val lat = getAttributeValue(null, "lat").toDouble()
        val lng = getAttributeValue(null, "lon").toDouble()
        var time: Instant? = null
        var desc: String? = null
        while (!(next() == XMLStreamConstants.END_ELEMENT && localName == "trkpt")) {
            if (eventType == XMLStreamConstants.START_ELEMENT) {
                when (localName) {
                    "time" -> time = Instant.parse(elementText)
                    "desc" -> desc = elementText
                }
            }
        }

        return TrackPoint(lat, lng, requireNotNull(time) { "Every <trkpt> needs a <time>" }, desc)
    }
}

fun List<TrackPoint>.toFixes(
    now: Instant,
    accuracyM: Double = 5.0,
): List<FixDto> {
    val shift = now - last().time
    return map {
        FixDto(
            lat = it.lat,
            lng = it.lng,
            accuracyM = accuracyM,
            recordedAt = it.time + shift,
            provider = LocationProviderDto.GMS_FUSED,
        )
    }
}
