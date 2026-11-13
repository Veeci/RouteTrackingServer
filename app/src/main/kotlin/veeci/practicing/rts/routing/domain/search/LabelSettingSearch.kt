package veeci.practicing.rts.routing.domain.search

import veeci.practicing.rts.routing.domain.RoadGraph

/** A lower bound on the remaining cost from a node to the target, in the units of the metric. */
internal fun interface Heuristic {
    fun estimate(node: Int): Double

    companion object {
        /** Estimating 0 everywhere turns A* into Dijkstra. */
        val ZERO = Heuristic { 0.0 }
    }
}

/**
 * The search loop that [Dijkstra] and [AStar] share. They differ only in the [Heuristic].
 *
 * The queue is ordered by `g(v) + h(v)`: `g` is the best cost found so far from the start and `h` the
 * heuristic. When `h` is consistent (`h(u) ≤ w(u, v) + h(v)` for every edge), the first time a node leaves
 * the queue its `g` is final. So the loop marks it settled, skips its later stale entries, and stops as soon
 * as the target leaves the queue.
 *
 * The arrays are sized to the graph and allocated per call: 29 bytes a node, so about 15 MB of
 * short-lived garbage on a 500 000-node graph. Reusing them per thread is a later optimisation if
 * profiling asks for it.
 */
internal class LabelSettingSearch(
    private val graph: RoadGraph,
    private val metric: Metric,
    private val heuristic: Heuristic,
    private val checkpoint: () -> Unit = {},
) {
    private val n = graph.nodeCount
    private val cost = DoubleArray(n) { Double.POSITIVE_INFINITY }
    private val lengthM = DoubleArray(n)
    private val timeS = DoubleArray(n)
    private val parent = IntArray(n) { NONE }
    private val settled = BooleanArray(n)
    private val heap = IntDoubleHeap()

    fun run(
        from: Int,
        to: Int,
    ): SearchResult? {
        require(from in 0 until n) { "from=$from is not a node of a graph with $n nodes" }
        require(to in 0 until n) { "to=$to is not a node of a graph with $n nodes" }
        cost[from] = 0.0
        heap.push(from, heuristic.estimate(from))
        var settledCount = 0
        while (!heap.isEmpty()) {
            val v = heap.pop()
            if (settled[v]) continue
            settled[v] = true
            settledCount++
            if ((settledCount and CHECKPOINT_MASK) == 0) checkpoint()
            if (v == to) return SearchResult(pathTo(to), lengthM[to], timeS[to], settledCount)
            relaxEdgesOf(v)
        }
        return null
    }

    private fun relaxEdgesOf(v: Int) {
        graph.forEachEdge(v) { w, edgeLengthM, speedMps ->
            if (!settled[w]) {
                val edgeTimeS = edgeLengthM.toDouble() / speedMps
                val candidate = cost[v] + if (metric == Metric.DISTANCE) edgeLengthM.toDouble() else edgeTimeS
                if (candidate < cost[w]) {
                    cost[w] = candidate
                    lengthM[w] = lengthM[v] + edgeLengthM
                    timeS[w] = timeS[v] + edgeTimeS
                    parent[w] = v
                    heap.push(w, candidate + heuristic.estimate(w))
                }
            }
        }
    }

    private fun pathTo(to: Int): IntArray {
        var count = 1
        var v = to
        while (parent[v] != NONE) {
            v = parent[v]
            count++
        }
        val path = IntArray(count)
        v = to
        for (i in count - 1 downTo 0) {
            path[i] = v
            v = parent[v]
        }
        return path
    }

    private companion object {
        const val NONE = -1
        const val CHECKPOINT_MASK = ShortestPath.CHECKPOINT_INTERVAL - 1
    }
}
