package veeci.practicing.rts.testing

import io.ktor.server.application.Application
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withTimeout
import org.koin.core.module.Module
import org.koin.ktor.ext.get
import veeci.practicing.rts.module
import veeci.practicing.rts.routing.application.RoadGraphHolder
import veeci.practicing.rts.testing.routing.TestRouting
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [block] against the real application (config loader, Koin graph, migrations, plugins) backed by the
 * shared test database. [env] overrides config values (e.g. `HTTP_RATELIMITPERMINUTE`); [routes] adds
 * test-only endpoints, so production code never carries them. [overrides] replaces Koin definitions (for example a
 * repository that waits on a gate).
 *
 * The road graph is the fixture of [TestRouting], and [block] starts once it has loaded, as a load balancer
 * would wait for readiness. [awaitRoadGraph] = false starts at once, for tests of the time before that.
 */
fun testApp(
    env: Map<String, String> = emptyMap(),
    routes: Routing.() -> Unit = {},
    overrides: List<Module> = emptyList(),
    awaitRoadGraph: Boolean = true,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    lateinit var app: Application
    application {
        app = this
        module(TestConfig.load(TestDatabase.env() + env), listOf(TestRouting.module()) + overrides)
        routing(routes)
    }
    if (awaitRoadGraph) {
        startApplication()
        app.awaitRoadGraph()
    }
    block()
}

/** Waits for the background graph load that Application.module starts. */
internal suspend fun Application.awaitRoadGraph() {
    withTimeout(GRAPH_LOAD_TIMEOUT) { get<RoadGraphHolder>().awaitLoaded() }
}

private val GRAPH_LOAD_TIMEOUT = 10.seconds
