package veeci.practicing.rts.routing.application.port.out

import veeci.practicing.rts.routing.domain.RoadGraph

/** Where the road graph comes from: an OSM file in production, a hand-written fixture in tests. */
interface RoadGraphSource {
    /** Names the source in logs, e.g. the file path. */
    val description: String

    /** Builds the graph. Slow (seconds for a city) and CPU-bound, so callers run it off the request threads. */
    fun load(): RoadGraph
}
