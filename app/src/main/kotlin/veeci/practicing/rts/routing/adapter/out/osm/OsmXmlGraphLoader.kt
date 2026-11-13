package veeci.practicing.rts.routing.adapter.out.osm

import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.routing.domain.RoadGraphBuilder
import veeci.practicing.rts.shared.geo.GeoPoint
import java.io.InputStream
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.io.path.inputStream

/**
 * Reads an OSM XML file (docs/data/osm.md) into a [RoadGraph].
 *
 * It uses StAX, a pull parser: the loader asks for one XML event at a time and keeps only the way it is
 * reading. A DOM parser would first build the whole tree, which for a 150 MB city file is several GB of heap.
 *
 * A malformed file throws [IllegalArgumentException] with the line number, or the parser's
 * [javax.xml.stream.XMLStreamException].
 */
class OsmXmlGraphLoader(
    private val pruneToLargestComponent: Boolean = true,
) {
    fun load(path: Path): RoadGraph = path.inputStream().buffered().use(::load)

    fun load(input: InputStream): RoadGraph {
        val builder = RoadGraphBuilder()
        val reader = newFactory().createXMLStreamReader(input)
        try {
            read(reader, builder)
        } finally {
            reader.close()
        }
        return builder.build(pruneToLargestComponent)
    }

    private fun read(
        reader: XMLStreamReader,
        builder: RoadGraphBuilder,
    ) {
        var way: WayDraft? = null
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    way = onStart(reader, builder, way)
                }

                XMLStreamConstants.END_ELEMENT -> {
                    if (reader.localName == "way") {
                        way?.addTo(builder)
                        way = null
                    }
                }
            }
        }
    }

    /** Returns the way being read after this element: a new one at `<way>`, otherwise [way] unchanged. */
    private fun onStart(
        reader: XMLStreamReader,
        builder: RoadGraphBuilder,
        way: WayDraft?,
    ): WayDraft? {
        when (reader.localName) {
            "node" -> builder.addNode(reader.long("id"), GeoPoint(reader.double("lat"), reader.double("lon")))

            "way" -> return WayDraft()

            "nd" -> way?.nodeOsmIds?.add(reader.long("ref"))

            // Tags of nodes and relations do not matter for the graph; only tags inside a <way> are kept.
            "tag" -> way?.tags?.put(reader.attribute("k"), reader.attribute("v"))
        }
        return way
    }

    private class WayDraft {
        val nodeOsmIds = ArrayList<Long>()
        val tags = HashMap<String, String>()

        fun addTo(builder: RoadGraphBuilder) {
            val highway = OsmTags.highwayType(tags["highway"]) ?: return
            builder.addWay(nodeOsmIds, highway, OsmTags.oneway(tags))
        }
    }

    private companion object {
        /**
         * DTDs and external entities are off: an OSM file needs neither, and they open the door to XXE attacks.
         * A new factory per load, because the StAX spec does not promise that a shared one is thread-safe.
         */
        fun newFactory(): XMLInputFactory =
            XMLInputFactory.newInstance().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }

        fun XMLStreamReader.attribute(name: String): String =
            requireNotNull(getAttributeValue(null, name)) { "OSM line ${location.lineNumber}: <$localName> has no '$name' attribute" }

        fun XMLStreamReader.long(name: String): Long =
            attribute(name).toLongOrNull()
                ?: throw IllegalArgumentException("OSM line ${location.lineNumber}: '$name' of <$localName> is not a whole number")

        fun XMLStreamReader.double(name: String): Double =
            attribute(name).toDoubleOrNull()
                ?: throw IllegalArgumentException("OSM line ${location.lineNumber}: '$name' of <$localName> is not a number")
    }
}
