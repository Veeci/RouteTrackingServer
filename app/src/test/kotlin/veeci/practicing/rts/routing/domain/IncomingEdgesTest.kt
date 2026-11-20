package veeci.practicing.rts.routing.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import veeci.practicing.rts.testing.routing.GraphFixtures

class IncomingEdgesTest {
    @Test
    fun `the reverse CSR holds every edge once, grouped by target, with its length and speed`() {
        val graph = GraphFixtures.load("one_way_trap")
        val forward = mutableListOf<Triple<Int, Int, Float>>()
        for (v in 0 until graph.nodeCount) graph.forEachEdge(v) { to, lengthM, _ -> forward += Triple(v, to, lengthM) }

        val reverse = mutableListOf<Triple<Int, Int, Float>>()
        for (v in 0 until graph.nodeCount) graph.incoming.forEachEdgeInto(v) { from, lengthM, _ -> reverse += Triple(from, v, lengthM) }

        reverse.sortedWith(compareBy({ it.first }, { it.second }, { it.third })) shouldBe
            forward.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        graph.incoming.offsets.last() shouldBe graph.edgeCount
    }

    @Test
    fun `the reverse CSR is built once per graph`() {
        val graph = GraphFixtures.load("tiny_grid")

        graph.incoming shouldBeSameInstanceAs graph.incoming
    }
}
