package veeci.practicing.rts.routing.adapter.out.osm

import veeci.practicing.rts.routing.application.port.out.RoadGraphSource
import veeci.practicing.rts.routing.domain.RoadGraph
import java.nio.file.Path

/** Reads the graph from an OSM XML file on disk (docs/data/osm.md says how to download one). */
class OsmFileGraphSource(
    private val path: Path,
    private val loader: OsmXmlGraphLoader = OsmXmlGraphLoader(),
) : RoadGraphSource {
    override val description: String get() = path.toAbsolutePath().toString()

    override fun load(): RoadGraph = loader.load(path)
}
