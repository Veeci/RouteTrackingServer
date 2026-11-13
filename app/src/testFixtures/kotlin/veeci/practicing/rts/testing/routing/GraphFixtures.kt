package veeci.practicing.rts.testing.routing

import veeci.practicing.rts.routing.adapter.out.osm.OsmXmlGraphLoader
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.testing.Fixtures

/** Loads the hand-written OSM files in `testFixtures/resources/graphs`. Each file draws its graph in a comment. */
object GraphFixtures {
    fun load(
        name: String,
        pruneToLargestComponent: Boolean = true,
    ): RoadGraph = Fixtures.stream("graphs/$name.osm").use { OsmXmlGraphLoader(pruneToLargestComponent).load(it) }
}

/** One directed edge, named by the OSM ids of its ends so that tests can compare with the fixture drawings. */
data class EdgeView(
    val fromOsmId: Long,
    val toOsmId: Long,
    val lengthM: Float,
    val speedMps: Float,
)

fun RoadGraph.edgeViews(): List<EdgeView> =
    (0 until nodeCount).flatMap { from ->
        buildList { forEachEdge(from) { to, lengthM, speedMps -> add(EdgeView(osmId(from), osmId(to), lengthM, speedMps)) } }
    }

/** Directed edges as `from to to` OSM id pairs. */
fun RoadGraph.edgePairs(): Set<Pair<Long, Long>> = edgeViews().map { it.fromOsmId to it.toOsmId }.toSet()

fun RoadGraph.osmIds(): Set<Long> = (0 until nodeCount).map { osmId(it) }.toSet()

fun RoadGraph.nodeOf(osmId: Long): Int = (0 until nodeCount).first { osmId(it) == osmId }
