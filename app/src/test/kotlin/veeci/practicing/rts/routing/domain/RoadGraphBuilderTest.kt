package veeci.practicing.rts.routing.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.testing.routing.edgePairs
import veeci.practicing.rts.testing.routing.edgeViews
import veeci.practicing.rts.testing.routing.osmIds

class RoadGraphBuilderTest {
    // A short street near Hoan Kiem Lake: A, B and C are about 100 m apart, going east.
    private val a = GeoPoint(21.0285, 105.8500)
    private val b = GeoPoint(21.0285, 105.8510)
    private val c = GeoPoint(21.0285, 105.8520)

    private fun builderWith(vararg nodes: Pair<Long, GeoPoint>) =
        RoadGraphBuilder().apply { nodes.forEach { (id, point) -> addNode(id, point) } }

    private fun abc() = builderWith(1L to a, 2L to b, 3L to c)

    @Test
    fun `TC-6-GR-01 a two-way way A-B-C gives four directed edges whose lengths are the haversine distances`() {
        val graph = abc().apply { addWay(listOf(1, 2, 3), HighwayType.RESIDENTIAL, Oneway.NO) }.build()

        graph.edgePairs() shouldBe setOf(1L to 2L, 2L to 1L, 2L to 3L, 3L to 2L)
        val ab = Haversine.distance(a, b).value.toFloat()
        val bc = Haversine.distance(b, c).value.toFloat()
        graph.edgeViews().associate { (it.fromOsmId to it.toOsmId) to it.lengthM } shouldBe
            mapOf((1L to 2L) to ab, (2L to 1L) to ab, (2L to 3L) to bc, (3L to 2L) to bc)
    }

    @Test
    fun `TC-6-GR-02 oneway FORWARD keeps only A to B and REVERSE keeps only B to A`() {
        val forward = abc().apply { addWay(listOf(1, 2), HighwayType.RESIDENTIAL, Oneway.FORWARD) }.build(pruneToLargestComponent = false)
        val reverse = abc().apply { addWay(listOf(1, 2), HighwayType.RESIDENTIAL, Oneway.REVERSE) }.build(pruneToLargestComponent = false)

        forward.edgePairs() shouldBe setOf(1L to 2L)
        reverse.edgePairs() shouldBe setOf(2L to 1L)
    }

    @Test
    fun `TC-6-GR-04 nodes on no way are dropped and the kept ones get dense ids in first-use order`() {
        val graph =
            builderWith(10L to a, 20L to b, 30L to c, 40L to GeoPoint(21.03, 105.86))
                .apply { addWay(listOf(30, 10), HighwayType.SERVICE, Oneway.NO) }
                .build()

        graph.nodeCount shouldBe 2
        (0 until graph.nodeCount).map { graph.osmId(it) } shouldContainExactly listOf(30L, 10L)
        graph.point(0) shouldBe c
        graph.point(1) shouldBe a
    }

    @Test
    fun `TC-6-GR-06 one-way dead ends and one-way entries are removed by SCC pruning`() {
        // Block 1-2-3-4 is two-way. 5 can be reached from 3 but has no way out; 6 leads into 1 but cannot be reached.
        val builder =
            builderWith(
                1L to GeoPoint(21.030, 105.850),
                2L to GeoPoint(21.030, 105.851),
                3L to GeoPoint(21.029, 105.851),
                4L to GeoPoint(21.029, 105.850),
                5L to GeoPoint(21.028, 105.851),
                6L to GeoPoint(21.031, 105.850),
            ).apply {
                addWay(listOf(1, 2, 3, 4, 1), HighwayType.RESIDENTIAL, Oneway.NO)
                addWay(listOf(3, 5), HighwayType.SERVICE, Oneway.FORWARD)
                addWay(listOf(6, 1), HighwayType.SERVICE, Oneway.FORWARD)
            }

        builder.build(pruneToLargestComponent = false).osmIds() shouldBe setOf(1L, 2L, 3L, 4L, 5L, 6L)
        val pruned = builder.build()
        pruned.osmIds() shouldBe setOf(1L, 2L, 3L, 4L)
        pruned.edgeCount shouldBe 8
    }

    @Test
    fun `an edge takes the default speed of its highway type`() {
        val graph = abc().apply { addWay(listOf(1, 2), HighwayType.PRIMARY, Oneway.NO) }.build()

        graph.edgeViews().map { it.speedMps }.distinct() shouldBe listOf(50 / 3.6f)
        HighwayType.RESIDENTIAL.speedMps shouldBe (8.333f plusOrMinus 0.001f)
    }

    @Test
    fun `a pair with an unknown node or a repeated node gives no edge, and the rest of the way is kept`() {
        val graph =
            abc()
                .apply {
                    addWay(
                        listOf(1, 1, 2, 99, 3),
                        HighwayType.RESIDENTIAL,
                        Oneway.NO,
                    )
                }.build(pruneToLargestComponent = false)

        graph.edgePairs() shouldBe setOf(1L to 2L, 2L to 1L)
        graph.osmIds() shouldBe setOf(1L, 2L)
    }

    @Test
    fun `a way with fewer than two nodes and an empty builder give an empty graph`() {
        val graph = abc().apply { addWay(listOf(1), HighwayType.RESIDENTIAL, Oneway.NO) }.build()

        graph.nodeCount shouldBe 0
        graph.edgeCount shouldBe 0
        RoadGraphBuilder().build().nodeCount shouldBe 0
    }

    @Test
    fun `SCC pruning of a 200 000 node chain does not overflow the stack`() {
        // A recursive Tarjan would nest 200 000 calls here and throw StackOverflowError.
        val n = 200_000
        val builder = RoadGraphBuilder()
        for (i in 0 until n) builder.addNode(i.toLong(), GeoPoint(20.0 + i * 1e-5, 105.85))
        builder.addWay((0 until n).map { it.toLong() }, HighwayType.RESIDENTIAL, Oneway.NO)

        val graph = builder.build()

        graph.nodeCount shouldBe n
        graph.edgeCount shouldBe 2 * (n - 1)
    }

    @Test
    fun `the graph refuses arrays that break the CSR shape`() {
        shouldThrow<IllegalArgumentException> {
            RoadGraph(LongArray(1), DoubleArray(1), DoubleArray(1), IntArray(2) { it }, IntArray(0), FloatArray(0), FloatArray(0))
        }
    }
}
