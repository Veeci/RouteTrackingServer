package veeci.practicing.rts.routing.application

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.platform.observability.HealthStatus
import veeci.practicing.rts.routing.adapter.inbound.health.GraphHealthIndicator
import veeci.practicing.rts.routing.adapter.out.osm.OsmFileGraphSource
import veeci.practicing.rts.routing.application.port.out.RoadGraphSource
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.testing.Fixtures
import veeci.practicing.rts.testing.routing.FixtureGraphSource
import java.nio.file.Files
import java.nio.file.Path

class RoadGraphLoaderTest {
    private val holder = RoadGraphHolder()
    private val health = GraphHealthIndicator(holder)

    @Test
    fun `the graph health check is DOWN until the graph is loaded, then UP`() {
        health.check() shouldBe HealthStatus.DOWN

        RoadGraphLoader(FixtureGraphSource("tiny_grid"), holder).load()

        holder.current
            .shouldNotBeNull()
            .graph.nodeCount shouldBe 9
        health.check() shouldBe HealthStatus.UP
    }

    @Test
    fun `a failed load leaves routing unavailable and does not throw`() {
        val broken =
            object : RoadGraphSource {
                override val description = "a broken source"

                override fun load(): RoadGraph = error("disk on fire")
            }

        RoadGraphLoader(broken, holder).load()

        holder.current.shouldBeNull()
        health.check() shouldBe HealthStatus.DOWN
    }

    @Test
    fun `the production source reads an OSM file from disk, and a missing file is a failed load`() {
        val file = Files.createTempFile("tiny_grid", ".osm")
        try {
            Files.writeString(file, Fixtures.text("graphs/tiny_grid.osm"))

            RoadGraphLoader(OsmFileGraphSource(file), holder).load()
            holder.current
                .shouldNotBeNull()
                .graph.nodeCount shouldBe 9

            val fresh = RoadGraphHolder()
            RoadGraphLoader(OsmFileGraphSource(Path.of("data/osm/does-not-exist.osm")), fresh).load()
            fresh.current.shouldBeNull()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
