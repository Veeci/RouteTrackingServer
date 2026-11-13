package veeci.practicing.rts.testing.routing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.dsl.module
import veeci.practicing.rts.routing.application.GraphRoutePlanner
import veeci.practicing.rts.routing.application.PlannerSettings
import veeci.practicing.rts.routing.application.RoadGraphHolder
import veeci.practicing.rts.routing.application.port.out.RoadGraphSource
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.routing.domain.search.AStar
import veeci.practicing.rts.routing.domain.search.ShortestPath

/** A [RoadGraphSource] that reads one of the hand-written OSM files in `testFixtures/resources/graphs`. */
class FixtureGraphSource(
    private val name: String,
) : RoadGraphSource {
    override val description: String get() = "test fixture graphs/$name.osm"

    override fun load(): RoadGraph = GraphFixtures.load(name)
}

/** A source whose load waits until the test calls [release]: for "before the graph is loaded" cases. */
class GatedGraphSource(
    private val delegate: RoadGraphSource,
) : RoadGraphSource {
    private val gate = CompletableDeferred<Unit>()

    override val description: String get() = "gated ${delegate.description}"

    override fun load(): RoadGraph {
        runBlocking { gate.await() }
        return delegate.load()
    }

    fun release() {
        gate.complete(Unit)
    }
}

/** Routing for tests: the graph every app test runs with, and planners built directly on fixtures. */
object TestRouting {
    /** Covers the Ho Chi Minh City points of TripSamples and the simulator routes (see the file's comment). */
    const val CITY_GRAPH = "saigon_test_grid"

    /** Replaces the OSM file of production with [source]. testApp and socketApp add it unless a test overrides it. */
    fun module(source: RoadGraphSource = FixtureGraphSource(CITY_GRAPH)): Module = module { single<RoadGraphSource> { source } }

    /** A planner on the fixture [graph], already loaded. */
    fun planner(
        graph: String = CITY_GRAPH,
        search: ShortestPath = AStar(),
        settings: PlannerSettings = PlannerSettings(),
    ): GraphRoutePlanner = GraphRoutePlanner(holderWith(GraphFixtures.load(graph)), search, settings)

    fun holderWith(graph: RoadGraph): RoadGraphHolder = RoadGraphHolder().apply { publish(graph) }
}
