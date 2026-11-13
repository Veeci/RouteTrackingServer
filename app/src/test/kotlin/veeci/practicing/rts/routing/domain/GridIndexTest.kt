package veeci.practicing.rts.routing.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.testing.routing.GraphFixtures
import veeci.practicing.rts.testing.routing.nodeOf
import veeci.practicing.rts.testing.routing.roadGraph
import kotlin.random.Random

class GridIndexTest {
    private val tinyGrid = GraphFixtures.load("tiny_grid")

    @Test
    fun `TC-6-IDX-01 a point exactly on a node returns that node at distance 0`() {
        val index = GridIndex(tinyGrid)
        val five = tinyGrid.nodeOf(5)

        val match = index.nearest(tinyGrid.point(five), maxDistanceM = 300.0)

        match shouldBe NodeMatch(five, 0.0)
    }

    @Test
    fun `TC-6-IDX-03 a point 1 km from every node with a 300 m limit returns null`() {
        val index = GridIndex(tinyGrid)
        // 0.009 degrees of latitude north of node 1 (the northernmost row) is about 1 km.
        val farNorth = GeoPoint(21.039, 105.850)

        index.nearest(farNorth, maxDistanceM = 300.0).shouldBeNull()
        index.nearest(farNorth, maxDistanceM = 1_100.0)?.node shouldBe tinyGrid.nodeOf(1)
    }

    @Test
    fun `TC-6-IDX-04 the nearest node in a neighbouring cell wins over a farther node in the query's own cell`() {
        // Three nodes on one east-west street, about 312 m apart. With 250 m cells: west in cell 0, middle in cell 1.
        val west = GeoPoint(21.0000, 105.8000)
        val middle = GeoPoint(21.0000, 105.8030)
        val east = GeoPoint(21.0000, 105.8060)
        val graph =
            RoadGraphBuilder()
                .apply {
                    addNode(1, west)
                    addNode(2, middle)
                    addNode(3, east)
                    addWay(listOf(1, 2, 3), HighwayType.RESIDENTIAL, Oneway.NO)
                }.build()
        val index = GridIndex(graph, cellSizeM = 250.0)
        // About 239 m east of west and 73 m west of middle.
        val query = GeoPoint(21.0000, 105.8023)

        // Check the setup, so the test cannot pass by accident if the cells move.
        index.cellOf(query) shouldBe index.cellOf(west)
        index.cellOf(query) shouldNotBe index.cellOf(middle)

        val match = index.nearest(query, maxDistanceM = 300.0).shouldNotBeNull()
        match.node shouldBe graph.nodeOf(2)
        match.distanceM shouldBe (Haversine.distance(query, middle).value plusOrMinus 1e-9)
    }

    @Test
    fun `TC-6-IDX-02 nearest matches a brute-force scan for random points and cell sizes`() =
        runTest {
            val config = PropTestConfig(seed = 20_261_113L)
            val cellSizes: Arb<Double> = Arb.double(20.0, 600.0, includeNaNs = false)
            checkAll(config, Arb.roadGraph(), cellSizes, Arb.long()) { graph, cellSizeM, seed ->
                val index = GridIndex(graph, cellSizeM)
                val random = Random(seed)
                repeat(QUERIES_PER_GRAPH) {
                    // The random grids span about 21.019..21.032 and 105.839..105.852. Query a little beyond that.
                    val query = GeoPoint(random.nextDouble(21.015, 21.036), random.nextDouble(105.835, 105.856))
                    val maxDistanceM = random.nextDouble(50.0, 800.0)
                    val expected = bruteForceNearest(graph, query)

                    val match = index.nearest(query, maxDistanceM)

                    if (expected > maxDistanceM) {
                        match.shouldBeNull()
                    } else {
                        // Compare distances, not ids: two nodes can be equally near.
                        match.shouldNotBeNull().distanceM shouldBe (expected plusOrMinus 1e-9)
                        Haversine.distance(query, graph.point(match.node)).value shouldBe (expected plusOrMinus 1e-9)
                    }
                }
            }
        }

    @Test
    fun `an empty graph has no nearest node`() {
        val empty = RoadGraphBuilder().build()

        GridIndex(empty).nearest(GeoPoint(21.0, 105.8), maxDistanceM = 1_000.0).shouldBeNull()
    }

    private fun bruteForceNearest(
        graph: RoadGraph,
        query: GeoPoint,
    ): Double = (0 until graph.nodeCount).minOf { Haversine.distance(query, graph.point(it)).value }

    private companion object {
        const val QUERIES_PER_GRAPH = 20
    }
}
