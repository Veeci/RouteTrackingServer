package veeci.practicing.rts.routing.domain.search

import veeci.practicing.rts.routing.domain.RoadGraph

/**
 * Dijkstra's algorithm: grows a ball of settled nodes around the start, cheapest first, until the target
 * is inside. It has no sense of direction, so it settles nodes behind the start as readily as nodes ahead.
 * Tests use it as the oracle for [AStar].
 */
class Dijkstra : ShortestPath {
    override fun search(
        graph: RoadGraph,
        from: Int,
        to: Int,
        metric: Metric,
    ): SearchResult? = LabelSettingSearch(graph, metric, Heuristic.ZERO).run(from, to)
}
