package veeci.practicing.rts.trip.adapter.inbound.web.export

import veeci.practicing.rts.trip.application.TrackExport
import java.io.OutputStream
import java.util.Locale
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/**
 * Writes one leg as GPX 1.1 (phase 8d): one `trk` with one `trkseg`, a `trkpt` with its `time` per fix. StAX
 * escapes every value and holds no document in memory, so the file is written page by page as the fixes arrive.
 * GPX 1.1 has no speed outside `<extensions>`, so speed is left out, and the timeline belongs to GeoJSON.
 */
object GpxWriter {
    const val CONTENT_TYPE = "application/gpx+xml"
    private const val NS = "http://www.topografix.com/GPX/1/1"

    // Indentation depth of a line, by the element it sits in.
    private const val ROOT = 0
    private const val GPX = 1
    private const val TRK = 2
    private const val TRKSEG = 3

    suspend fun write(
        export: TrackExport,
        out: OutputStream,
    ) {
        val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out, Charsets.UTF_8.name())
        xml.writeStartDocument(Charsets.UTF_8.name(), "1.0")
        xml.line(ROOT)
        xml.writeStartElement("gpx")
        xml.writeDefaultNamespace(NS)
        xml.writeAttribute("version", "1.1")
        xml.writeAttribute("creator", "RouteTrackingServer")
        xml.line(GPX)
        xml.writeStartElement("trk")
        xml.line(TRK)
        xml.element("name", "trip-${export.tripId.value} ${export.leg.name.lowercase()}")
        xml.line(TRK)
        xml.writeStartElement("trkseg")
        export.pages.collect { page ->
            page.forEach {
                xml.line(TRKSEG)
                xml.writeStartElement("trkpt")
                xml.writeAttribute("lat", coordinate(it.lat))
                xml.writeAttribute("lon", coordinate(it.lng))
                xml.element("time", it.recordedAt.toString())
                xml.writeEndElement()
            }
            xml.flush() // one page at a time reaches the client
        }
        xml.line(TRK)
        xml.writeEndElement() // trkseg
        xml.line(GPX)
        xml.writeEndElement() // trk
        xml.line(ROOT)
        xml.writeEndElement() // gpx
        xml.line(ROOT)
        xml.writeEndDocument()
        xml.close() // closes the writer, not the stream: the route owns it
    }

    private fun XMLStreamWriter.element(
        name: String,
        text: String,
    ) {
        writeStartElement(name)
        writeCharacters(text)
        writeEndElement()
    }

    /** A newline and two spaces per [depth], so the file reads well and diffs cleanly. */
    private fun XMLStreamWriter.line(depth: Int) = writeCharacters("\n" + "  ".repeat(depth))
}

/** Degrees with 7 decimals (about 1 cm), never in exponent form. */
internal fun coordinate(degrees: Double): String = String.format(Locale.ROOT, "%.7f", degrees)
