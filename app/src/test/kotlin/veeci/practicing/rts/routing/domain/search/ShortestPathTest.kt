package veeci.practicing.rts.routing.domain.search

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import veeci.practicing.rts.routing.domain.HighwayType
import veeci.practicing.rts.routing.domain.Oneway
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.routing.domain.RoadGraphBuilder
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.routing.GraphFixtures
import veeci.practicing.rts.testing.routing.edgePairs
import veeci.practicing.rts.testing.routing.nodeOf

/**
 * Known answers on the hand-written fixtures. Every expected cost here was computed outside this code and is
 * written in the fixture's comment block. Each test runs once with Dijkstra and once with A*.
 */
class ShortestPathTest {
    @ParameterizedTest
    @MethodSource("algorithms")
    fun `TC-6-SP-01 tiny_grid corner to corner costs the hand-computed minimum along a valid chain`(search: ShortestPath) {
        val graph = GraphFixtures.load("tiny_grid")

        val result = search.search(graph, graph.nodeOf(1), graph.nodeOf(9), Metric.DISTANCE).shouldNotBeNull()

        result.costM shouldBe (429.968 plusOrMinus 0.001)
        result.osmPath(graph) shouldContainExactly listOf(1L, 2L, 3L, 6L, 9L)
        result.shouldBeAChainIn(graph)
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    fun `TC-6-SP-02 one_way_trap takes the legal detour instead of the short road against the one-way`(search: ShortestPath) {
        val graph = GraphFixtures.load("one_way_trap")
        val s = graph.nodeOf(1)
        val t = graph.nodeOf(3)

        val there = search.search(graph, s, t, Metric.DISTANCE).shouldNotBeNull()
        val back = search.search(graph, t, s, Metric.DISTANCE).shouldNotBeNull()

        there.osmPath(graph) shouldContainExactly listOf(1L, 2L, 3L)
        there.costM shouldBe (304.214 plusOrMinus 0.001)
        back.osmPath(graph) shouldContainExactly listOf(3L, 1L)
        back.costM shouldBe (207.579 plusOrMinus 0.001)
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    fun `TC-6-SP-03 from equal to to gives a one-node path with zero cost`(search: ShortestPath) {
        val graph = GraphFixtures.load("tiny_grid")
        val five = graph.nodeOf(5)

        val result = search.search(graph, five, five, Metric.TIME).shouldNotBeNull()

        result shouldBe SearchResult(intArrayOf(five), costM = 0.0, costS = 0.0, settled = 1)
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    fun `TC-6-SP-04 a target on another island is unreachable and gives null`(search: ShortestPath) {
        val graph = GraphFixtures.load("two_islands", pruneToLargestComponent = false)

        search.search(graph, graph.nodeOf(1), graph.nodeOf(11), Metric.DISTANCE).shouldBeNull()
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    fun `TC-6-SP-05 TIME takes a longer primary road when it is faster than the direct residential one`(search: ShortestPath) {
        // A to B direct: residential (30 km/h), about 1038 m, so about 124.6 s.
        // A to C to B: primary (50 km/h), about 1129 m, so about 81.3 s.
        val graph =
            RoadGraphBuilder()
                .apply {
                    addNode(1, GeoPoint(21.000, 105.800))
                    addNode(2, GeoPoint(21.000, 105.810))
                    addNode(3, GeoPoint(21.002, 105.805))
                    addWay(listOf(1, 2), HighwayType.RESIDENTIAL, Oneway.NO)
                    addWay(listOf(1, 3, 2), HighwayType.PRIMARY, Oneway.NO)
                }.build()
        val a = graph.nodeOf(1)
        val b = graph.nodeOf(2)

        val shortest = search.search(graph, a, b, Metric.DISTANCE).shouldNotBeNull()
        val fastest = search.search(graph, a, b, Metric.TIME).shouldNotBeNull()

        shortest.osmPath(graph) shouldContainExactly listOf(1L, 2L)
        fastest.osmPath(graph) shouldContainExactly listOf(1L, 3L, 2L)
        fastest.costM shouldBe (1129.0 plusOrMinus 1.0)
        fastest.costS shouldBe (81.3 plusOrMinus 0.1)
        (fastest.costS < shortest.costS) shouldBe true
        (fastest.costM > shortest.costM) shouldBe true
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    fun `a node id outside the graph is rejected`(search: ShortestPath) {
        val graph = GraphFixtures.load("tiny_grid")

        shouldThrow<IllegalArgumentException> { search.search(graph, 0, graph.nodeCount, Metric.DISTANCE) }
        shouldThrow<IllegalArgumentException> { search.search(graph, -1, 0, Metric.DISTANCE) }
    }

    private fun SearchResult.osmPath(graph: RoadGraph): List<Long> = nodes.map { graph.osmId(it) }

    private fun SearchResult.shouldBeAChainIn(graph: RoadGraph) {
        val edges = graph.edgePairs()
        nodes.toList().zipWithNext().forEach { (a, b) -> edges.contains(graph.osmId(a) to graph.osmId(b)) shouldBe true }
    }

    companion object {
        @JvmStatic
        fun algorithms(): List<ShortestPath> = listOf(Dijkstra(), AStar())
    }
}
