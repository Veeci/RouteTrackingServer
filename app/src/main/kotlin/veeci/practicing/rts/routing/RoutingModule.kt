package veeci.practicing.rts.routing

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import veeci.practicing.rts.platform.config.RoutingConfig
import veeci.practicing.rts.platform.observability.HealthIndicator
import veeci.practicing.rts.routing.adapter.inbound.health.GraphHealthIndicator
import veeci.practicing.rts.routing.adapter.out.osm.CachedGraphSource
import veeci.practicing.rts.routing.adapter.out.osm.OsmFileGraphSource
import veeci.practicing.rts.routing.application.GraphRoutePlanner
import veeci.practicing.rts.routing.application.PlannerSettings
import veeci.practicing.rts.routing.application.RoadGraphHolder
import veeci.practicing.rts.routing.application.RoadGraphLoader
import veeci.practicing.rts.routing.application.api.RoutePlanner
import veeci.practicing.rts.routing.application.port.out.RoadGraphSource
import veeci.practicing.rts.routing.domain.search.AStar
import veeci.practicing.rts.routing.domain.search.ShortestPath
import java.nio.file.Path

/**
 * The routing context's object graph. Other contexts see only [RoutePlanner]. The graph source is a definition of
 * its own, so tests replace the OSM file with a fixture without touching anything else.
 */
fun routingModule(config: RoutingConfig): Module =
    module {
        single { RoadGraphHolder() }
        single<RoadGraphSource> { graphSource(config) }
        single { RoadGraphLoader(get(), get()) }
        // A* settles about a quarter of Dijkstra's nodes on the DISTANCE metric and returns the same cost (6b).
        single<ShortestPath> { AStar() }
        single<RoutePlanner> {
            GraphRoutePlanner(get(), get(), PlannerSettings(config.maxSnapDistanceM, config.coverageMarginM, config.planTimeout))
        }
        single { GraphHealthIndicator(get()) } bind HealthIndicator::class
    }

/** The OSM file through the binary cache, or the OSM file alone when `routing.graphCacheDir` is empty. */
private fun graphSource(config: RoutingConfig): RoadGraphSource {
    val osm = Path.of(config.graphPath)
    return if (config.graphCacheDir.isBlank()) OsmFileGraphSource(osm) else CachedGraphSource(osm, Path.of(config.graphCacheDir))
}
