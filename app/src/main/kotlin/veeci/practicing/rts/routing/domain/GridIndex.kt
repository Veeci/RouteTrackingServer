package veeci.practicing.rts.routing.domain

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the graph node nearest to a point, by bucketing nodes into square cells of [cellSizeM].
 *
 * Positions are projected to flat metres: `y = R · lat` and `x = R · cos(φ) · lng`, where φ is the latitude
 * of the graph farthest from the equator. Inside the graph's latitude band that projection never makes an
 * east-west distance longer than it is on the sphere, so a projected distance is a lower bound for the
 * haversine distance. The search relies on that: it can stop once the nearest node found is closer than
 * any cell it has not yet visited.
 *
 * Cells are stored like the edges of [RoadGraph]: [cellStart] holds offsets into one flat [cellNodes] array.
 * Immutable after construction, so threads may share one index.
 */
class GridIndex(
    private val graph: RoadGraph,
    val cellSizeM: Double = DEFAULT_CELL_SIZE_M,
) {
    private val metresPerRadianX: Double
    private val originX: Double
    private val originY: Double
    private val columns: Int
    private val rows: Int
    private val cellStart: IntArray
    private val cellNodes: IntArray

    init {
        require(cellSizeM > 0) { "cellSizeM must be positive, was $cellSizeM" }
        val n = graph.nodeCount
        var maxAbsLat = 0.0
        for (v in 0 until n) maxAbsLat = max(maxAbsLat, abs(graph.nodeLat[v]))
        metresPerRadianX = Haversine.EARTH_RADIUS_M * cos(Math.toRadians(maxAbsLat))

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (v in 0 until n) {
            minX = min(minX, x(graph.nodeLng[v]))
            minY = min(minY, y(graph.nodeLat[v]))
            maxX = max(maxX, x(graph.nodeLng[v]))
            maxY = max(maxY, y(graph.nodeLat[v]))
        }
        originX = if (n == 0) 0.0 else minX
        originY = if (n == 0) 0.0 else minY
        columns = if (n == 0) 1 else ((maxX - minX) / cellSizeM).toInt() + 1
        rows = if (n == 0) 1 else ((maxY - minY) / cellSizeM).toInt() + 1
        require(columns.toLong() * rows <= MAX_CELLS) {
            "A ${columns}x$rows grid is too large; the graph should cover one city. Use larger cells."
        }

        // Counting sort of nodes by cell, the same way RoadGraphBuilder sorts edges by source node.
        val cellOfNode = IntArray(n) { v -> cellIndex(column(graph.nodeLng[v]), row(graph.nodeLat[v])) }
        cellStart = IntArray(columns * rows + 1)
        for (cell in cellOfNode) cellStart[cell + 1]++
        for (c in 0 until columns * rows) cellStart[c + 1] += cellStart[c]
        val next = cellStart.copyOf(columns * rows)
        cellNodes = IntArray(n)
        for (v in 0 until n) cellNodes[next[cellOfNode[v]]++] = v
    }

    /**
     * The node nearest to [point] by haversine distance, or null when no node lies within [maxDistanceM].
     * When several nodes are equally near, any one of them may be returned.
     *
     * Searches ring 0 (the point's own cell), then ring 1 (the 8 cells around it), and so on. Every cell of
     * ring `r + 1` is at least `r · cellSizeM + gap` away, where `gap` is the distance from the point to the
     * nearest side of its own cell. The search stops when the best node so far is no farther than that, or
     * when that bound passes [maxDistanceM].
     */
    fun nearest(
        point: GeoPoint,
        maxDistanceM: Double,
    ): NodeMatch? {
        if (graph.nodeCount == 0) return null
        val search = RingSearch(point, maxDistanceM)
        val qx = x(point.lng) - originX
        val qy = y(point.lat) - originY
        val col = floor(qx / cellSizeM).toInt()
        val row = floor(qy / cellSizeM).toInt()
        val gap = minOf(qx - col * cellSizeM, (col + 1) * cellSizeM - qx, qy - row * cellSizeM, (row + 1) * cellSizeM - qy)
        val lastRing = maxOf(abs(col), abs(columns - 1 - col), abs(row), abs(rows - 1 - row))

        var ring = 0
        while (ring <= lastRing) {
            visitRing(col, row, ring, search)
            val nextRingAtLeast = (ring * cellSizeM + gap) * BOUND_SAFETY
            if (search.bestDistanceM <= nextRingAtLeast || nextRingAtLeast > maxDistanceM) break
            ring++
        }
        return search.result()
    }

    /** The cell that holds [point], as `column to row`. For tests that need to place points in chosen cells. */
    internal fun cellOf(point: GeoPoint): Pair<Int, Int> = column(point.lng) to row(point.lat)

    private fun visitRing(
        col: Int,
        row: Int,
        ring: Int,
        search: RingSearch,
    ) {
        if (ring == 0) {
            visitCell(col, row, search)
            return
        }
        for (c in col - ring..col + ring) {
            visitCell(c, row - ring, search)
            visitCell(c, row + ring, search)
        }
        for (r in row - ring + 1..row + ring - 1) {
            visitCell(col - ring, r, search)
            visitCell(col + ring, r, search)
        }
    }

    private fun visitCell(
        col: Int,
        row: Int,
        search: RingSearch,
    ) {
        if (col !in 0 until columns || row !in 0 until rows) return
        val cell = cellIndex(col, row)
        for (slot in cellStart[cell] until cellStart[cell + 1]) search.offer(cellNodes[slot])
    }

    /** The best node so far for one query. One instance per call, so concurrent queries share nothing. */
    private inner class RingSearch(
        private val point: GeoPoint,
        private val maxDistanceM: Double,
    ) {
        var bestNode = -1
            private set
        var bestDistanceM = Double.POSITIVE_INFINITY
            private set

        fun offer(node: Int) {
            val d = Haversine.distanceM(point.lat, point.lng, graph.nodeLat[node], graph.nodeLng[node])
            if (d < bestDistanceM) {
                bestDistanceM = d
                bestNode = node
            }
        }

        fun result(): NodeMatch? = if (bestNode >= 0 && bestDistanceM <= maxDistanceM) NodeMatch(bestNode, bestDistanceM) else null
    }

    private fun x(lng: Double) = Math.toRadians(lng) * metresPerRadianX

    private fun y(lat: Double) = Math.toRadians(lat) * Haversine.EARTH_RADIUS_M

    private fun column(lng: Double) = floor((x(lng) - originX) / cellSizeM).toInt()

    private fun row(lat: Double) = floor((y(lat) - originY) / cellSizeM).toInt()

    private fun cellIndex(
        col: Int,
        row: Int,
    ) = row * columns + col

    companion object {
        const val DEFAULT_CELL_SIZE_M = 250.0

        /** Hanoi's bounding box at 250 m is about 150 x 150 cells; this cap only stops a mistaken whole-country load. */
        private const val MAX_CELLS = 16_000_000L

        /**
         * Shrinks the ring bound a little. The projection is exact only at one latitude, so a query just outside
         * the graph's latitude band, or rounding in the trigonometry, could make the bound a hair too large.
         */
        private const val BOUND_SAFETY = 0.999
    }
}

/** A snapped node and the haversine distance from the query point to it. */
data class NodeMatch(
    val node: Int,
    val distanceM: Double,
)
