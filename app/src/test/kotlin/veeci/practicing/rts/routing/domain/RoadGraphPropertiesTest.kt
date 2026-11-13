package veeci.practicing.rts.routing.domain

import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.geo.GeoPoint

/**
 * Properties over random small road networks. Up to 30 nodes, so a brute-force reachability check can act
 * as an oracle for Tarjan.
 */
class RoadGraphPropertiesTest {
    private class RandomNetwork(
        val nodeCount: Int,
        val ways: List<Triple<List<Long>, HighwayType, Oneway>>,
    ) {
        fun builder(): RoadGraphBuilder =
            RoadGraphBuilder().apply {
                for (id in 0 until nodeCount) addNode(id.toLong(), GeoPoint(21.0 + id * 1e-3, 105.8 + (id % 5) * 1e-3))
                ways.forEach { (ids, highway, oneway) -> addWay(ids, highway, oneway) }
            }

        override fun toString() = "RandomNetwork(nodes=$nodeCount, ways=$ways)"
    }

    private val networks =
        arbitrary {
            val n = Arb.int(2..30).bind()
            val way =
                arbitrary { Triple(Arb.list(Arb.long(0L until n), 2..5).bind(), Arb.enum<HighwayType>().bind(), Arb.enum<Oneway>().bind()) }
            RandomNetwork(n, Arb.list(way, 1..40).bind())
        }

    private val config = PropTestConfig(seed = 20_261_113L)

    @Test
    fun `TC-6-GR-07 CSR invariants hold with and without pruning`() =
        runTest {
            checkAll(config, networks, Arb.boolean()) { network, prune ->
                val g = network.builder().build(prune)

                g.edgeOffsets.size shouldBe g.nodeCount + 1
                g.edgeOffsets.first() shouldBe 0
                g.edgeOffsets.toList().shouldBeSortedWith(naturalOrder())
                g.edgeOffsets.last() shouldBe g.edgeCount
                g.edgeTargets.forEach { it shouldBeLessThan g.nodeCount }
                g.edgeLengthM.forEach { (it >= 0f) shouldBe true }
            }
        }

    @Test
    fun `after pruning every node reaches every other, and no larger component existed`() =
        runTest {
            checkAll(config, networks) { network ->
                val full = network.builder().build(pruneToLargestComponent = false)
                val pruned = network.builder().build()

                val forward = adjacency(pruned)
                val backward = reverse(forward)
                if (pruned.nodeCount > 0) {
                    reachableFrom(forward, 0).all { it } shouldBe true
                    reachableFrom(backward, 0).all { it } shouldBe true
                }
                pruned.nodeCount shouldBe bruteForceLargestComponent(full)
            }
        }

    private fun adjacency(g: RoadGraph): List<List<Int>> =
        (0 until g.nodeCount).map { v -> buildList { g.forEachEdge(v) { to, _, _ -> add(to) } } }

    private fun reverse(adjacency: List<List<Int>>): List<List<Int>> {
        val result = List(adjacency.size) { mutableListOf<Int>() }
        adjacency.forEachIndexed { from, targets -> targets.forEach { result[it] += from } }
        return result
    }

    /** Breadth-first search; returns which nodes [start] can reach. */
    private fun reachableFrom(
        adjacency: List<List<Int>>,
        start: Int,
    ): BooleanArray {
        val seen = BooleanArray(adjacency.size)
        val queue = ArrayDeque(listOf(start))
        seen[start] = true
        while (queue.isNotEmpty()) {
            for (to in adjacency[queue.removeFirst()]) {
                if (!seen[to]) {
                    seen[to] = true
                    queue.addLast(to)
                }
            }
        }
        return seen
    }

    /** u and v share a component when each reaches the other. O(n^3), fine for 30 nodes. */
    private fun bruteForceLargestComponent(g: RoadGraph): Int {
        val adjacency = adjacency(g)
        val reach = Array(g.nodeCount) { reachableFrom(adjacency, it) }
        return (0 until g.nodeCount).maxOfOrNull { v -> (0 until g.nodeCount).count { u -> reach[v][u] && reach[u][v] } } ?: 0
    }
}
