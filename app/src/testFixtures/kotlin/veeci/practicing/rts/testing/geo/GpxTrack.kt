package veeci.practicing.rts.testing.geo

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Instant

/** One `trkpt` of a GPX file. */
data class GpxPoint(
    val lat: Double,
    val lng: Double,
    val time: Instant?,
)

/**
 * Reads the track points of a GPX 1.1 file, in file order, for tests of the exports (phase 8d). Namespace-aware,
 * so a file without the GPX namespace has no points. DOCTYPEs are refused: a test file never needs one.
 */
object GpxTrack {
    private const val NS = "http://www.topografix.com/GPX/1/1"

    fun points(gpx: InputStream): List<GpxPoint> {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        val document = factory.newDocumentBuilder().parse(gpx)
        val nodes = document.getElementsByTagNameNS(NS, "trkpt")
        return (0 until nodes.length).map { i ->
            val point = nodes.item(i) as Element
            val time = point.getElementsByTagNameNS(NS, "time").item(0)?.textContent
            GpxPoint(point.getAttribute("lat").toDouble(), point.getAttribute("lon").toDouble(), time?.let(Instant::parse))
        }
    }

    fun points(gpx: String): List<GpxPoint> = points(gpx.byteInputStream())
}
