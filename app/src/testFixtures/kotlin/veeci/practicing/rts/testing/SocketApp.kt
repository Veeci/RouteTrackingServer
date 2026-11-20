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
import java.net.ServerSocket

/**
 * Like [testApp], but the application runs on Netty and clients connect over a real socket (CIO), as in
 * production. For WebSocket tests: Ktor's in-memory test engine sometimes upgrades a socket (101) without ever
 * starting its handler (about 1 connection in 150), which made socket tests flaky; on Netty it does not happen.
 *
 * [adminPort] = true starts a second connector and sets `HTTP_ADMINPORT` to its port, as staging does (phase 12a): the
 * admin routes then answer only at [SocketTestApp.adminUrl]. The routes are built before the server binds, so the
 * admin port cannot be 0 like the public one: the fixture asks the OS for a free port first.
 */
fun socketApp(
    env: Map<String, String> = emptyMap(),
    overrides: List<Module> = emptyList(),
    adminPort: Boolean = false,
    block: suspend SocketTestApp.() -> Unit,
): Unit =
    runBlocking {
        val admin = if (adminPort) freePort() else null
        val config = TestConfig.load(TestDatabase.env() + env + admin?.let { mapOf("HTTP_ADMINPORT" to "$it") }.orEmpty())
        val server =
            embeddedServer(
                Netty,
                configure = {
                    connector { port = 0 }
                    admin?.let { connector { port = it } }
                },
            ) {
                module(config, listOf(TestRouting.module()) + overrides)
            }.startSuspend()
        try {
            server.application.awaitRoadGraph()
            val port =
                server.engine
                    .resolvedConnectors()
                    .first { it.port != admin }
                    .port
            val app = SocketTestApp(server.application, "http://localhost:$port", admin?.let { "http://localhost:$it" })
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
    /** `http://localhost:<port>`, for clients that build their own URLs (the simulator's fleet). */
    val baseUrl: String,
    /** `http://localhost:<admin port>` when the test started one (`socketApp(adminPort = true)`), else null. */
    val adminUrl: String? = null,
) {
    private val clients = mutableListOf<HttpClient>()

    /** A client for the server at [target]: relative URLs (`/ws/v1/driver`) go to it. Closed when the test ends. */
    fun createClient(
        target: String = baseUrl,
        block: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient =
        HttpClient(CIO) {
            defaultRequest { url(target) }
            block()
        }.also { clients += it }

    /** A plain client, for REST calls next to the sockets. */
    val client: HttpClient by lazy { createClient() }

    /** A plain client for the admin port. Only with `socketApp(adminPort = true)`. */
    val adminClient: HttpClient by lazy { createClient(checkNotNull(adminUrl) { "Start the test with socketApp(adminPort = true)" }) }

    internal fun closeClients() = clients.forEach { it.close() }
}

private const val STOP_TIMEOUT_MS = 2_000L

/**
 * A port nothing listens on right now. Another process could take it before the server binds it. That is rare with
 * ports from the OS's ephemeral range, and the test then fails at start, not with a wrong result.
 */
private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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
