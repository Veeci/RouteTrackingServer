package veeci.practicing.rts.routing.domain

import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine

/**
 * Collects OSM nodes and ways in any order, then builds a [RoadGraph].
 *
 * Build rules (docs/plan/phase-06-routing.md):
 * - Only nodes used by a way are kept. OSM ids become dense ids `0 until n`, in the order they first
 *   appear in the ways.
 * - Each pair of neighbouring way nodes gives one edge per allowed direction ([Oneway]). Its length is the
 *   haversine distance and its speed is the [HighwayType] default.
 * - A pair is skipped when a node was never added, or when the same node repeats.
 * - [build] keeps only the largest strongly connected component, so every node can reach every other.
 *
 * Not thread-safe. Use one builder per load.
 */
class RoadGraphBuilder {
    private val points = HashMap<Long, GeoPoint>()
    private val ways = ArrayList<Way>()

    fun addNode(
        osmId: Long,
        point: GeoPoint,
    ) {
        points[osmId] = point
    }

    fun addWay(
        nodeOsmIds: List<Long>,
        highway: HighwayType,
        oneway: Oneway,
    ) {
        if (nodeOsmIds.size >= 2) ways += Way(nodeOsmIds.toLongArray(), highway, oneway)
    }

    /**
     * @param pruneToLargestComponent false keeps every node and edge. Only tests need that, to show what
     *   pruning removes or to search a graph with unreachable nodes.
     */
    fun build(pruneToLargestComponent: Boolean = true): RoadGraph {
        val full = buildFull()
        return if (pruneToLargestComponent) full.keepOnly(StrongComponents.largest(full)) else full
    }

    private fun buildFull(): RoadGraph {
        val denseIds = HashMap<Long, Int>()
        val nodes = NodeBuffer()
        val edges = EdgeBuffer()

        fun denseId(
            osmId: Long,
            point: GeoPoint,
        ): Int = denseIds.getOrPut(osmId) { nodes.add(osmId, point) }

        for (way in ways) {
            val speed = way.highway.speedMps
            for (i in 1 until way.nodeOsmIds.size) {
                val a = way.nodeOsmIds[i - 1]
                val b = way.nodeOsmIds[i]
                val pa = points[a]
                val pb = points[b]
                if (a == b || pa == null || pb == null) continue
                val from = denseId(a, pa)
                val to = denseId(b, pb)
                val lengthM = Haversine.distance(pa, pb).value.toFloat()
                if (way.oneway != Oneway.REVERSE) edges.add(from, to, lengthM, speed)
                if (way.oneway != Oneway.FORWARD) edges.add(to, from, lengthM, speed)
            }
        }
        return edges.toGraph(nodes)
    }

    private class Way(
        val nodeOsmIds: LongArray,
        val highway: HighwayType,
        val oneway: Oneway,
    )

    /** Growable primitive arrays, so that a million nodes do not become a million boxed values. */
    private class NodeBuffer {
        var size = 0
            private set
        var osmIds = LongArray(INITIAL_CAPACITY)
        var lats = DoubleArray(INITIAL_CAPACITY)
        var lngs = DoubleArray(INITIAL_CAPACITY)

        fun add(
            osmId: Long,
            point: GeoPoint,
        ): Int {
            if (size == osmIds.size) {
                osmIds = osmIds.copyOf(size * 2)
                lats = lats.copyOf(size * 2)
                lngs = lngs.copyOf(size * 2)
            }
            osmIds[size] = osmId
            lats[size] = point.lat
            lngs[size] = point.lng
            return size++
        }
    }

    private class EdgeBuffer {
        var size = 0
            private set
        var from = IntArray(INITIAL_CAPACITY)
        var to = IntArray(INITIAL_CAPACITY)
        var lengthM = FloatArray(INITIAL_CAPACITY)
        var speedMps = FloatArray(INITIAL_CAPACITY)

        fun add(
            from: Int,
            to: Int,
            lengthM: Float,
            speedMps: Float,
        ) {
            if (size == this.from.size) {
                this.from = this.from.copyOf(size * 2)
                this.to = this.to.copyOf(size * 2)
                this.lengthM = this.lengthM.copyOf(size * 2)
                this.speedMps = this.speedMps.copyOf(size * 2)
            }
            this.from[size] = from
            this.to[size] = to
            this.lengthM[size] = lengthM
            this.speedMps[size] = speedMps
            size++
        }

        /** Counting sort by source node: count edges per node, turn counts into offsets, then place each edge. */
        fun toGraph(nodes: NodeBuffer): RoadGraph {
            val n = nodes.size
            val offsets = IntArray(n + 1)
            for (e in 0 until size) offsets[from[e] + 1]++
            for (v in 0 until n) offsets[v + 1] += offsets[v]
            val next = offsets.copyOf(n)
            val targets = IntArray(size)
            val lengths = FloatArray(size)
            val speeds = FloatArray(size)
            for (e in 0 until size) {
                val slot = next[from[e]]++
                targets[slot] = to[e]
                lengths[slot] = lengthM[e]
                speeds[slot] = speedMps[e]
            }
            return RoadGraph(
                nodes.osmIds.copyOf(n),
                nodes.lats.copyOf(n),
                nodes.lngs.copyOf(n),
                offsets,
                targets,
                lengths,
                speeds,
            )
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}

/**
 * The subgraph of the nodes in [keep] and the edges between them, with node ids made dense again.
 * Nodes keep their relative order, so the result is still sorted by source node and needs no second sort.
 */
internal fun RoadGraph.keepOnly(keep: BooleanArray): RoadGraph {
    val newId = IntArray(nodeCount) { -1 }
    var kept = 0
    for (v in 0 until nodeCount) if (keep[v]) newId[v] = kept++

    val osmIds = LongArray(kept)
    val lats = DoubleArray(kept)
    val lngs = DoubleArray(kept)
    val offsets = IntArray(kept + 1)
    var keptEdges = 0
    for (v in 0 until nodeCount) {
        if (keep[v]) forEachEdge(v) { to, _, _ -> if (keep[to]) keptEdges++ }
    }
    val targets = IntArray(keptEdges)
    val lengths = FloatArray(keptEdges)
    val speeds = FloatArray(keptEdges)

    var e = 0
    for (v in 0 until nodeCount) {
        val id = newId[v]
        if (id < 0) continue
        osmIds[id] = osmId(v)
        lats[id] = nodeLat[v]
        lngs[id] = nodeLng[v]
        for (slot in edgeOffsets[v] until edgeOffsets[v + 1]) {
            if (keep[edgeTargets[slot]]) {
                targets[e] = newId[edgeTargets[slot]]
                lengths[e] = edgeLengthM[slot]
                speeds[e] = edgeSpeedMps[slot]
                e++
            }
        }
        offsets[id + 1] = e
    }
    return RoadGraph(osmIds, lats, lngs, offsets, targets, lengths, speeds)
}
