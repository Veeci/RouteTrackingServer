package veeci.practicing.rts.routing.domain.search

import veeci.practicing.rts.routing.domain.RoadGraph

/** What a search minimises. */
enum class Metric {
    /** Metres along the road. */
    DISTANCE,

    /** Seconds at each road's default speed ([veeci.practicing.rts.routing.domain.HighwayType]). */
    TIME,
}

/**
 * A point-to-point shortest path search on a [RoadGraph].
 *
 * Implementations keep no state between calls, so one instance may serve concurrent requests.
 */
interface ShortestPath {
    /**
     * Returns the cheapest path from [from] to [to] under [metric], or null when [to] cannot be reached.
     *
     * The search calls [checkpoint] once every [CHECKPOINT_INTERVAL] settled nodes. A caller that wants to stop
     * a long search (a deadline, a cancelled request) throws from it; the exception leaves this function as is.
     * The domain knows nothing about coroutines, so this plain callback is how cancellation reaches the loop.
     * @throws IllegalArgumentException when a node id is not in the graph.
     */
    fun search(
        graph: RoadGraph,
        from: Int,
        to: Int,
        metric: Metric,
        checkpoint: () -> Unit = {},
    ): SearchResult?

    companion object {
        /** A power of two, so the loop tests it with a bit mask. About 0.1 ms of work on a city graph. */
        const val CHECKPOINT_INTERVAL = 1024
    }
}

/**
 * One path, as dense node ids from the start node to the target node.
 *
 * Both costs belong to this one path, whatever the metric: a TIME search still reports the path's length.
 * [settled] counts the nodes the search took off its queue for good, which shows how much work it did.
 */
class SearchResult(
    val nodes: IntArray,
    val costM: Double,
    val costS: Double,
    val settled: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is SearchResult &&
            nodes.contentEquals(other.nodes) &&
            costM == other.costM &&
            costS == other.costS &&
            settled == other.settled

    override fun hashCode(): Int = 31 * (31 * (31 * nodes.contentHashCode() + costM.hashCode()) + costS.hashCode()) + settled

    override fun toString(): String = "SearchResult(nodes=${nodes.contentToString()}, costM=$costM, costS=$costS, settled=$settled)"
}
