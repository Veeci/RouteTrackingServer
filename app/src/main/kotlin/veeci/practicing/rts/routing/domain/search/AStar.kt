package veeci.practicing.rts.routing.domain.search

import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.shared.geo.Haversine

/**
 * A*: Dijkstra with the queue ordered by `cost so far + estimate of the cost left`, so the search leans
 * toward the target and settles fewer nodes.
 *
 * The estimate is the straight-line (haversine) distance to the target. For [Metric.TIME] it is that
 * distance divided by the graph's fastest speed. No road is shorter than the straight line and no road is
 * faster than the fastest one, so the estimate never exceeds the true remaining cost (it is admissible).
 * By the triangle inequality it is also consistent, which is what lets [LabelSettingSearch] treat a node
 * as final the first time it leaves the queue. With that, A* returns the same cost as Dijkstra.
 */
class AStar : ShortestPath {
    override fun search(
        graph: RoadGraph,
        from: Int,
        to: Int,
        metric: Metric,
        checkpoint: () -> Unit,
    ): SearchResult? {
        require(to in 0 until graph.nodeCount) { "to=$to is not a node of a graph with ${graph.nodeCount} nodes" }
        return LabelSettingSearch(graph, metric, heuristic(graph, to, metric), checkpoint).run(from, to)
    }

    internal companion object {
        /**
         * Edge lengths are stored as `Float`, so a stored length can be about 6e-8 of itself shorter than the
         * haversine distance it came from. Scaling the estimate down by 1e-6 keeps it below every stored path.
         */
        private const val FLOAT_SLACK = 1.0 - 1e-6

        fun heuristic(
            graph: RoadGraph,
            target: Int,
            metric: Metric,
        ): Heuristic {
            val targetLat = graph.nodeLat[target]
            val targetLng = graph.nodeLng[target]
            val divisor =
                when (metric) {
                    Metric.DISTANCE -> 1.0
                    Metric.TIME -> graph.maxSpeedMps.toDouble()
                }
            if (divisor <= 0.0) return Heuristic.ZERO
            val scale = FLOAT_SLACK / divisor
            return Heuristic { node ->
                Haversine.distanceM(graph.nodeLat[node], graph.nodeLng[node], targetLat, targetLng) * scale
            }
        }
    }
}
