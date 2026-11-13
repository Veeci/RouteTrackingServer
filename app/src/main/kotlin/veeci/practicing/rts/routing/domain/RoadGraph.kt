package veeci.practicing.rts.routing.domain

import veeci.practicing.rts.shared.geo.GeoPoint

/**
 * A directed road graph in compressed sparse row (CSR) form. Nodes are dense ints `0 until nodeCount`.
 * The edges leaving node `v` are the slots `edgeOffsets[v] until edgeOffsets[v + 1]` of the edge arrays.
 *
 * Why arrays and not `Node` and `Edge` objects: a city has a few hundred thousand nodes. Primitive arrays
 * take several times less memory and a search reads them in order, which the CPU cache rewards.
 *
 * The graph is immutable after [RoadGraphBuilder.build], so any number of threads may read it without locks.
 * The arrays are `internal` so that nothing outside this module can change them. They are
 * [PublishedApi] only so that the inline [forEachEdge] can read them.
 */
class RoadGraph internal constructor(
    private val nodeOsmIds: LongArray,
    @PublishedApi internal val nodeLat: DoubleArray,
    @PublishedApi internal val nodeLng: DoubleArray,
    /** Size `nodeCount + 1`. Non-decreasing, starts at 0 and ends at [edgeCount]. */
    @PublishedApi internal val edgeOffsets: IntArray,
    @PublishedApi internal val edgeTargets: IntArray,
    @PublishedApi internal val edgeLengthM: FloatArray,
    @PublishedApi internal val edgeSpeedMps: FloatArray,
) {
    init {
        val n = nodeOsmIds.size
        require(nodeLat.size == n && nodeLng.size == n) { "Node arrays differ in size" }
        require(edgeOffsets.size == n + 1) { "edgeOffsets must have nodeCount + 1 entries" }
        require(edgeLengthM.size == edgeTargets.size && edgeSpeedMps.size == edgeTargets.size) { "Edge arrays differ in size" }
        require(edgeOffsets[n] == edgeTargets.size) { "The last offset must equal the edge count" }
    }

    val nodeCount: Int get() = nodeOsmIds.size

    val edgeCount: Int get() = edgeTargets.size

    /**
     * The fastest edge speed in the graph, or 0 when there are no edges. A* divides straight-line distance by
     * it to get a travel time that no real path can beat. Computed once here so that no search scans the edges.
     */
    val maxSpeedMps: Float = edgeSpeedMps.maxOrNull() ?: 0f

    fun point(node: Int): GeoPoint = GeoPoint(nodeLat[node], nodeLng[node])

    /** The OSM id the node had in the source file, for logs and tests. */
    fun osmId(node: Int): Long = nodeOsmIds[node]

    fun outDegree(node: Int): Int = edgeOffsets[node + 1] - edgeOffsets[node]

    /** Calls [block] once for each edge leaving [from]. Inline, so a search loop allocates no lambda per node. */
    inline fun forEachEdge(
        from: Int,
        block: (to: Int, lengthM: Float, speedMps: Float) -> Unit,
    ) {
        for (e in edgeOffsets[from] until edgeOffsets[from + 1]) {
            block(edgeTargets[e], edgeLengthM[e], edgeSpeedMps[e])
        }
    }
}
