package veeci.practicing.rts.routing.adapter.inbound.health

import veeci.practicing.rts.platform.observability.HealthIndicator
import veeci.practicing.rts.platform.observability.HealthStatus
import veeci.practicing.rts.routing.application.RoadGraphHolder

/** DOWN until the road graph has loaded: an instance that cannot plan routes cannot book trips either. */
class GraphHealthIndicator(
    private val graphs: RoadGraphHolder,
) : HealthIndicator {
    override val name = "graph"

    override fun check(): HealthStatus = if (graphs.current != null) HealthStatus.UP else HealthStatus.DOWN
}
