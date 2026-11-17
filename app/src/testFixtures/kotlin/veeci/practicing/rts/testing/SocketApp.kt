package veeci.practicing.rts.testing

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
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

/**
 * Two instances of the app on Netty, like two servers behind a load balancer (phase 10): each has its own Koin
 * graph, and both share one Redis and one fresh database, which no other test writes to. Without the fresh
 * database, the other tests' outbox events would reach these instances' relays. [env] is added to both.
 */
fun twoInstances(
    env: Map<String, String> = emptyMap(),
    block: suspend TwoInstances.() -> Unit,
): Unit =
    runBlocking {
        val shared = TestDatabase.freshDatabaseEnv() + TestRedis.env() + env
        val instances = TwoInstances(start(shared), start(shared), shared)
        try {
            instances.block()
        } finally {
            instances.stopAll()
        }
    }

/** What a [twoInstances] test sees: [a] and [b], and the database they share. */
class TwoInstances internal constructor(
    private val first: Instance,
    private val second: Instance,
    /** DB_URL, DB_USER and DB_PASSWORD of the shared database, for checks with plain SQL. */
    val dbEnv: Map<String, String>,
) {
    val a: SocketTestApp get() = first.app
    val b: SocketTestApp get() = second.app

    /** `ws://localhost:<port>` of [a] and [b], for clients that connect to an absolute URL. */
    val wsA: String get() = first.wsUrl
    val wsB: String get() = second.wsUrl

    /** Stops [a] at once, as a crash or a deploy would: its sockets get close 1001 and it delivers no more events. */
    suspend fun stopA() = first.stop()

    internal suspend fun stopAll() {
        first.stop()
        second.stop()
    }
}

internal class Instance(
    private val server: EmbeddedServer<*, *>,
    val app: SocketTestApp,
    val wsUrl: String,
) {
    private var stopped = false

    suspend fun stop() {
        if (stopped) return
        stopped = true
        app.closeClients()
        server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
    }
}

private suspend fun start(env: Map<String, String>): Instance {
    val server =
        embeddedServer(Netty, configure = { connector { port = 0 } }) {
            module(TestConfig.load(env), listOf(TestRouting.module()))
        }.startSuspend()
    server.application.awaitRoadGraph()
    val port =
        server.engine
            .resolvedConnectors()
            .first()
            .port
    return Instance(server, SocketTestApp(server.application, "http://localhost:$port"), "ws://localhost:$port")
}
