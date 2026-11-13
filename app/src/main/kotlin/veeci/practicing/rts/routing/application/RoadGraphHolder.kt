package veeci.practicing.rts.routing.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import veeci.practicing.rts.routing.domain.GeoBounds
import veeci.practicing.rts.routing.domain.GridIndex
import veeci.practicing.rts.routing.domain.RoadGraph

/** A graph together with what the planner derives from it once: the spatial index and the bounding box. */
class LoadedRoadGraph(
    val graph: RoadGraph,
) {
    val index = GridIndex(graph)
    val bounds = GeoBounds.of(graph)
}

/**
 * Holds the current road graph. Empty until the startup load finishes.
 *
 * A [LoadedRoadGraph] is immutable, so readers share it without locks. Replacing it is one write of a reference,
 * which the JVM makes atomic and [MutableStateFlow] makes visible to every thread: a request that already read
 * the old graph finishes on it, and the next request sees the new one. That is what a later reload (P2) needs.
 */
class RoadGraphHolder {
    private val state = MutableStateFlow<LoadedRoadGraph?>(null)

    /** The graph to plan on now, or null while it is still loading. */
    val current: LoadedRoadGraph? get() = state.value

    /** Builds the index for [graph] on the calling thread, then makes it current. */
    fun publish(graph: RoadGraph) {
        state.value = LoadedRoadGraph(graph)
    }

    /** Suspends until a graph is loaded. For startup code and tests that must not race the loader. */
    suspend fun awaitLoaded(): LoadedRoadGraph = state.filterNotNull().first()
}
