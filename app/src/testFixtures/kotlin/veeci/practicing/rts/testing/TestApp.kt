package veeci.practicing.rts.testing

import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import veeci.practicing.rts.module

/**
 * Runs [block] against the real application (config loader, Koin graph, migrations, plugins) backed by the
 * shared test database. [env] overrides config values (e.g. `HTTP_RATELIMITPERMINUTE`); [routes] adds
 * test-only endpoints, so production code never carries them.
 */
fun testApp(
    env: Map<String, String> = emptyMap(),
    routes: Routing.() -> Unit = {},
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        module(TestConfig.load(TestDatabase.env() + env))
        routing(routes)
    }
    block()
}
