package veeci.practicing.rts.routing.domain.search

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.testing.routing.roadGraph
import kotlin.random.Random

/**
 * Properties over random city-like grids ([roadGraph]). Dijkstra is the oracle for A*: they share the search
 * loop, but Dijkstra has no heuristic, so a wrong heuristic shows up as a cost difference.
 */
class ShortestPathPropertiesTest {
    private val dijkstra = Dijkstra()
    private val aStar = AStar()
    private val config = PropTestConfig(seed = 20_261_113L)

    @Test
    fun `TC-6-SP-06 and 07 A-star costs the same as Dijkstra and settles no more nodes`() =
        runTest {
            checkAll(config, Arb.roadGraph(), Arb.enum<Metric>(), Arb.long()) { graph, metric, seed ->
                randomPairs(graph, seed).forEach { (from, to) ->
                    val oracle = dijkstra.search(graph, from, to, metric).shouldNotBeNull()
                    val result = aStar.search(graph, from, to, metric).shouldNotBeNull()

                    result.cost(metric) shouldBe (oracle.cost(metric) plusOrMinus 1e-6)
                    result.settled shouldBeLessThanOrEqual oracle.settled
                }
            }
        }

    @Test
    fun `TC-6-SP-08 the path is a chain of real edges whose lengths and times add up to the reported costs`() =
        runTest {
            checkAll(config, Arb.roadGraph(), Arb.enum<Metric>(), Arb.long()) { graph, metric, seed ->
                randomPairs(graph, seed).forEach { (from, to) ->
                    listOf(dijkstra, aStar).forEach { search ->
                        val result = search.search(graph, from, to, metric).shouldNotBeNull()

                        result.nodes.first() shouldBe from
                        result.nodes.last() shouldBe to
                        var lengthM = 0.0
                        var timeS = 0.0
                        result.nodes.toList().zipWithNext().forEach { (a, b) ->
                            val edge = graph.cheapestEdge(a, b, metric).shouldNotBeNull()
                            lengthM += edge.first
                            timeS += edge.first / edge.second
                        }
                        result.costM shouldBe (lengthM plusOrMinus 1e-6)
                        result.costS shouldBe (timeS plusOrMinus 1e-6)
                    }
                }
            }
        }

    @Test
    fun `TC-6-SP-09 on two-way streets the cost from a to b equals the cost from b to a`() =
        runTest {
            checkAll(config, Arb.roadGraph(oneWayShare = 0.0), Arb.enum<Metric>(), Arb.long()) { graph, metric, seed ->
                randomPairs(graph, seed).forEach { (a, b) ->
                    val there = aStar.search(graph, a, b, metric).shouldNotBeNull()
                    val back = aStar.search(graph, b, a, metric).shouldNotBeNull()

                    there.cost(metric) shouldBe (back.cost(metric) plusOrMinus 1e-6)
                }
            }
        }

    @Test
    fun `TC-6-SP-10 the A-star estimate never exceeds the true remaining cost`() =
        runTest {
            checkAll(config, Arb.roadGraph(), Arb.enum<Metric>(), Arb.long()) { graph, metric, seed ->
                randomPairs(graph, seed).forEach { (node, target) ->
                    val estimate = AStar.heuristic(graph, target, metric).estimate(node)
                    val trueCost = dijkstra.search(graph, node, target, metric).shouldNotBeNull().cost(metric)

                    estimate shouldBeLessThanOrEqual trueCost
                }
            }
        }

    /**
     * Not a pass-fail property but the number the Definition of Done asks for. On a 30 x 30 grid A* should
     * settle clearly fewer nodes; the ratio goes to the test's standard output for the PR description.
     */
    @Test
    fun `A-star settles fewer nodes than Dijkstra on average`() =
        runTest {
            checkAll(PropTestConfig(seed = 20_261_113L, iterations = 5), Arb.roadGraph(sizes = 30..30), Arb.long()) { graph, seed ->
                Metric.entries.forEach { metric ->
                    var dijkstraSettled = 0L
                    var aStarSettled = 0L
                    randomPairs(graph, seed, count = 100).forEach { (from, to) ->
                        dijkstraSettled += dijkstra.search(graph, from, to, metric).shouldNotBeNull().settled
                        aStarSettled += aStar.search(graph, from, to, metric).shouldNotBeNull().settled
                    }
                    println("settled ratio A*/Dijkstra, $metric, ${graph.nodeCount} nodes: ${aStarSettled.toDouble() / dijkstraSettled}")
                    aStarSettled.toInt() shouldBeLessThan dijkstraSettled.toInt()
                }
            }
        }

    private fun randomPairs(
        graph: RoadGraph,
        seed: Long,
        count: Int = PAIRS_PER_GRAPH,
    ): List<Pair<Int, Int>> {
        val random = Random(seed)
        return List(count) { random.nextInt(graph.nodeCount) to random.nextInt(graph.nodeCount) }
    }

    private fun SearchResult.cost(metric: Metric): Double =
        when (metric) {
            Metric.DISTANCE -> costM
            Metric.TIME -> costS
        }

    /**
     * The edge a search would use from [a] to [b] as `lengthM to speedMps`, or null when there is none.
     * Two ways can join the same pair of nodes, so pick the cheapest under [metric], as the search does.
     */
    private fun RoadGraph.cheapestEdge(
        a: Int,
        b: Int,
        metric: Metric,
    ): Pair<Double, Double>? {
        var best: Pair<Double, Double>? = null
        forEachEdge(a) { to, lengthM, speedMps ->
            if (to == b) {
                val candidate = lengthM.toDouble() to speedMps.toDouble()
                val cost = if (metric == Metric.DISTANCE) candidate.first else candidate.first / candidate.second
                val bestCost = best?.let { if (metric == Metric.DISTANCE) it.first else it.first / it.second }
                if (bestCost == null || cost < bestCost) best = candidate
            }
        }
        return best
    }

    private companion object {
        const val PAIRS_PER_GRAPH = 10
    }
}
