package veeci.practicing.rts.testing

import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.core.module.Module
import veeci.practicing.rts.module

/**
 * Runs [block] against the real application (config loader, Koin graph, migrations, plugins) backed by the
 * shared test database. [env] overrides config values (e.g. `HTTP_RATELIMITPERMINUTE`); [routes] adds
 * test-only endpoints, so production code never carries them. [overrides] replaces Koin definitions (for example a
 * repository that waits on a gate).
 */
fun testApp(
    env: Map<String, String> = emptyMap(),
    routes: Routing.() -> Unit = {},
    overrides: List<Module> = emptyList(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        module(TestConfig.load(TestDatabase.env() + env), overrides)
        routing(routes)
    }
    block()
}
