package veeci.practicing.rts.routing.domain.search

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.routing.domain.HighwayType
import veeci.practicing.rts.routing.domain.Oneway
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.routing.domain.RoadGraphBuilder
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.routing.nodeOf

/** The checkpoint is how a caller stops a long search; the planner's timeout depends on it. */
class SearchCheckpointTest {
    private val grid = squareGrid(SIDE)
    private val corner = grid.nodeOf(id(0, 0))
    private val farCorner = grid.nodeOf(id(SIDE - 1, SIDE - 1))

    @Test
    fun `the search calls the checkpoint once per 1024 settled nodes`() {
        var calls = 0

        val result = Dijkstra().search(grid, corner, farCorner, Metric.DISTANCE) { calls++ }

        // Corner to corner, Dijkstra settles every node of the grid before the far corner.
        val settled = result.shouldNotBeNull().settled
        settled shouldBe SIDE * SIDE
        calls shouldBe settled / ShortestPath.CHECKPOINT_INTERVAL
    }

    @Test
    fun `an exception thrown by the checkpoint ends the search and reaches the caller`() {
        class Stop : RuntimeException()

        shouldThrow<Stop> { Dijkstra().search(grid, corner, farCorner, Metric.TIME) { throw Stop() } }
    }

    private fun squareGrid(side: Int): RoadGraph {
        val builder = RoadGraphBuilder()
        for (r in 0 until side) {
            for (c in 0 until side) builder.addNode(id(r, c), GeoPoint(21.0 + r * STEP, 105.8 + c * STEP))
        }
        for (r in 0 until side) {
            for (c in 0 until side) {
                if (c + 1 < side) builder.addWay(listOf(id(r, c), id(r, c + 1)), HighwayType.RESIDENTIAL, Oneway.NO)
                if (r + 1 < side) builder.addWay(listOf(id(r, c), id(r + 1, c)), HighwayType.RESIDENTIAL, Oneway.NO)
            }
        }
        return builder.build()
    }

    private fun id(
        row: Int,
        column: Int,
    ): Long = (row * SIDE + column).toLong()

    private companion object {
        /** 33 x 33 = 1089 nodes: just over one checkpoint interval. */
        const val SIDE = 33
        const val STEP = 0.001
    }
}
