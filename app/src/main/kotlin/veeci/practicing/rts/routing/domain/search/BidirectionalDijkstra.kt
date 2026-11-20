package veeci.practicing.rts.routing.domain.search

import veeci.practicing.rts.routing.domain.RoadGraph

/**
 * Bidirectional Dijkstra: one Dijkstra grows forward from the start on outgoing edges, a second grows backward
 * from the target on incoming edges ([RoadGraph.incoming]), and each step advances the side whose queue holds the
 * smaller key. Two balls of radius r/2 cover far fewer nodes than one ball of radius r.
 *
 * Every time a side lowers the label of a node, the search checks that node: if the other side has reached it
 * too, `forward + backward` there is the cost of a real path, and the search keeps the cheapest one as `best`.
 * Labels only go down and every decrease is checked, so `best` is always the cheapest meeting seen so far.
 *
 * The stopping rule is the subtle part. Stopping when the two balls first touch is wrong: the first node both
 * sides settle need not lie on the shortest path. The search stops when `minKey(forward) + minKey(backward) ≥
 * best`. Any path cheaper than `best` would have to leave each ball through a node still in its queue, so it would
 * cost at least the sum of the two smallest keys. Once that sum reaches `best`, no such path exists.
 *
 * [SearchResult.settled] counts the nodes both sides settled.
 */
class BidirectionalDijkstra : ShortestPath {
    override fun search(
        graph: RoadGraph,
        from: Int,
        to: Int,
        metric: Metric,
        checkpoint: () -> Unit,
    ): SearchResult? {
        val n = graph.nodeCount
        require(from in 0 until n) { "from=$from is not a node of a graph with $n nodes" }
        require(to in 0 until n) { "to=$to is not a node of a graph with $n nodes" }
        val forward = Side(graph, metric, backward = false)
        val backward = Side(graph, metric, backward = true)
        val meeting = Meeting()
        forward.start(from)
        backward.start(to)
        meeting.offer(from, forward, backward)

        var settledCount = 0
        while (forward.minKey() + backward.minKey() < meeting.cost) {
            val side = if (forward.minKey() <= backward.minKey()) forward else backward
            val v = side.pop()
            settledCount++
            if ((settledCount and CHECKPOINT_MASK) == 0) checkpoint()
            side.relaxEdgesOf(v) { w -> meeting.offer(w, forward, backward) }
        }
        val node = meeting.node
        if (node == NONE) return null
        return SearchResult(
            nodes = (forward.pathFromStart(node) + backward.pathToTarget(node).drop(1)).toIntArray(),
            costM = forward.lengthM[node] + backward.lengthM[node],
            costS = forward.timeS[node] + backward.timeS[node],
            settled = settledCount,
        )
    }

    /** The cheapest path found so far, as the node where the two sides meet and its total cost. */
    private class Meeting {
        var node = NONE
            private set
        var cost = Double.POSITIVE_INFINITY
            private set

        fun offer(
            v: Int,
            forward: Side,
            backward: Side,
        ) {
            val total = forward.cost[v] + backward.cost[v]
            if (total < cost) {
                cost = total
                node = v
            }
        }
    }

    /**
     * One direction of the search: the same arrays and lazy-deletion heap as [LabelSettingSearch]. A backward side
     * walks incoming edges, so its `parent` of a node is the next node on the way to the target.
     */
    private class Side(
        private val graph: RoadGraph,
        private val metric: Metric,
        private val backward: Boolean,
    ) {
        private val n = graph.nodeCount
        val cost = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val lengthM = DoubleArray(n)
        val timeS = DoubleArray(n)
        private val parent = IntArray(n) { NONE }
        private val settled = BooleanArray(n)
        private val heap = IntDoubleHeap()

        fun start(node: Int) {
            cost[node] = 0.0
            heap.push(node, 0.0)
        }

        /** The smallest key of a node not yet settled, or +∞. Stale entries of settled nodes are dropped first. */
        fun minKey(): Double {
            while (!heap.isEmpty() && settled[heap.peek()]) heap.pop()
            return heap.minKey()
        }

        /** Settles the node with the smallest key. Call only after [minKey] returned a finite key. */
        fun pop(): Int {
            val v = heap.pop()
            settled[v] = true
            return v
        }

        inline fun relaxEdgesOf(
            v: Int,
            onLowered: (Int) -> Unit,
        ) {
            if (backward) {
                graph.incoming.forEachEdgeInto(v) { w, edgeLengthM, speedMps -> relax(v, w, edgeLengthM, speedMps, onLowered) }
            } else {
                graph.forEachEdge(v) { w, edgeLengthM, speedMps -> relax(v, w, edgeLengthM, speedMps, onLowered) }
            }
        }

        inline fun relax(
            v: Int,
            w: Int,
            edgeLengthM: Float,
            speedMps: Float,
            onLowered: (Int) -> Unit,
        ) {
            if (settled[w]) return
            val edgeTimeS = edgeLengthM.toDouble() / speedMps
            val candidate = cost[v] + if (metric == Metric.DISTANCE) edgeLengthM.toDouble() else edgeTimeS
            if (candidate < cost[w]) {
                cost[w] = candidate
                lengthM[w] = lengthM[v] + edgeLengthM
                timeS[w] = timeS[v] + edgeTimeS
                parent[w] = v
                heap.push(w, candidate)
                onLowered(w)
            }
        }

        /** Forward side: the nodes from the start to [node], in driving order. */
        fun pathFromStart(node: Int): List<Int> = chainFrom(node).asReversed()

        /** Backward side: the nodes from [node] to the target, in driving order. */
        fun pathToTarget(node: Int): List<Int> = chainFrom(node)

        private fun chainFrom(node: Int): MutableList<Int> {
            val chain = ArrayList<Int>()
            var v = node
            while (v != NONE) {
                chain += v
                v = parent[v]
            }
            return chain
        }
    }

    private companion object {
        const val NONE = -1
        const val CHECKPOINT_MASK = ShortestPath.CHECKPOINT_INTERVAL - 1
    }
}
