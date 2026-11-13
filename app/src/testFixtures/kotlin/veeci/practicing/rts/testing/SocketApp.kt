package veeci.practicing.rts.testing

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.server.application.Application
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import veeci.practicing.rts.module
import veeci.practicing.rts.testing.routing.TestRouting

/**
 * Like [testApp], but the application runs on Netty and clients connect over a real socket (CIO), as in
 * production. For WebSocket tests: Ktor's in-memory test engine sometimes upgrades a socket (101) without ever
 * starting its handler (about 1 connection in 150), which made socket tests flaky; on Netty it does not happen.
 */
fun socketApp(
    env: Map<String, String> = emptyMap(),
    overrides: List<Module> = emptyList(),
    block: suspend SocketTestApp.() -> Unit,
): Unit =
    runBlocking {
        val server =
            embeddedServer(Netty, configure = { connector { port = 0 } }) {
                module(TestConfig.load(TestDatabase.env() + env), listOf(TestRouting.module()) + overrides)
            }.startSuspend()
        try {
            server.application.awaitRoadGraph()
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            val app = SocketTestApp(server.application, "http://localhost:$port")
            try {
                app.block()
            } finally {
                app.closeClients()
            }
        } finally {
            server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
        }
    }

/** What a [socketApp] test sees: the running application and clients that talk to it. */
class SocketTestApp internal constructor(
    val application: Application,
    private val baseUrl: String,
) {
    private val clients = mutableListOf<HttpClient>()

    /** A client for this server: relative URLs (`/ws/v1/driver`) go to it. Closed when the test ends. */
    fun createClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient =
        HttpClient(CIO) {
            defaultRequest { url(baseUrl) }
            block()
        }.also { clients += it }

    /** A plain client, for REST calls next to the sockets. */
    val client: HttpClient by lazy { createClient() }

    internal fun closeClients() = clients.forEach { it.close() }
}

private const val STOP_TIMEOUT_MS = 2_000L
