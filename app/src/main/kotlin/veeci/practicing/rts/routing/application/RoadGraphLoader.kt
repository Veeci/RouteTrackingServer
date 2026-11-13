package veeci.practicing.rts.routing.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import veeci.practicing.rts.routing.application.port.out.RoadGraphSource
import kotlin.time.TimeSource

/**
 * Loads the road graph once at startup, in the background.
 *
 * The server starts answering before the graph is ready: liveness is UP, so the platform does not restart a
 * process that is only busy parsing a map. Readiness stays DOWN (the `graph` check) until [RoadGraphHolder] has
 * a graph, so the load balancer sends it no traffic yet.
 */
class RoadGraphLoader(
    private val source: RoadGraphSource,
    private val holder: RoadGraphHolder,
) {
    private val log = LoggerFactory.getLogger(RoadGraphLoader::class.java)

    /** Starts the load on the CPU pool. Parsing XML and building CSR arrays never suspends, so IO would be wrong. */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Default) { load() }

    /**
     * A failed load is logged and leaves routing unavailable; the process stays up. Exiting would turn a bad map
     * path into a restart loop, while a DOWN readiness check with an error log tells the operator what is wrong.
     */
    @Suppress("TooGenericExceptionCaught") // any failure of the parser or the file means the same thing here
    internal fun load() {
        val started = TimeSource.Monotonic.markNow()
        try {
            val graph = source.load()
            holder.publish(graph)
            log.info(
                "Road graph loaded from {}: {} nodes, {} edges in {}",
                source.description,
                graph.nodeCount,
                graph.edgeCount,
                started.elapsedNow(),
            )
        } catch (e: Exception) {
            log.error("Could not load the road graph from {}; routing stays unavailable", source.description, e)
        }
    }
}
