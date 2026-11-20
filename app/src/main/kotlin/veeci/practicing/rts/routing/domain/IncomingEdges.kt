package veeci.practicing.rts.routing.domain

/**
 * The reverse of a [RoadGraph]: the same edges in CSR form, grouped by target node instead of source node.
 * The edges entering node `v` are the slots `offsets[v] until offsets[v + 1]`, and `sources[slot]` is where
 * each one starts. A backward search walks these to grow a ball of nodes that can reach the target.
 *
 * About 12 bytes per edge plus 4 per node, so roughly 15 MB for a city of a million edges. Immutable.
 */
internal class IncomingEdges(
    graph: RoadGraph,
) {
    val offsets = IntArray(graph.nodeCount + 1)
    val sources = IntArray(graph.edgeCount)
    val lengthM = FloatArray(graph.edgeCount)
    val speedMps = FloatArray(graph.edgeCount)

    init {
        // Counting sort by target node, the way RoadGraphBuilder sorts by source node.
        val n = graph.nodeCount
        for (e in 0 until graph.edgeCount) offsets[graph.edgeTargets[e] + 1]++
        for (v in 0 until n) offsets[v + 1] += offsets[v]
        val next = offsets.copyOf(n)
        for (from in 0 until n) {
            for (e in graph.edgeOffsets[from] until graph.edgeOffsets[from + 1]) {
                val slot = next[graph.edgeTargets[e]]++
                sources[slot] = from
                lengthM[slot] = graph.edgeLengthM[e]
                speedMps[slot] = graph.edgeSpeedMps[e]
            }
        }
    }

    /** Calls [block] once for each edge that ends at [to]. */
    inline fun forEachEdgeInto(
        to: Int,
        block: (from: Int, lengthM: Float, speedMps: Float) -> Unit,
    ) {
        for (slot in offsets[to] until offsets[to + 1]) {
            block(sources[slot], lengthM[slot], speedMps[slot])
        }
    }
}
