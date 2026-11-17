package veeci.practicing.rts.simulator.fleet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FleetClientMessage
import veeci.practicing.rts.protocol.FleetFilters
import veeci.practicing.rts.protocol.FleetSubscribe
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.simulator.BatchHooks
import veeci.practicing.rts.simulator.DriverClient
import veeci.practicing.rts.simulator.TrackPoint
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** `simulator fleet` settings. The defaults are the manual run of TC-11-LOAD-01 against a Hanoi deployment. */
data class FleetOptions(
    /** One or more instances. Driver and dashboard n go to instance n modulo the count, like a load balancer would. */
    val baseUrls: List<String> = listOf("http://localhost:8080"),
    val drivers: Int = 0,
    val dashboards: Int = 0,
    val duration: Duration = 60.seconds,
    /** minLng, minLat, maxLng, maxLat: the area every dashboard watches. Default: Hanoi's inner districts and more. */
    val bbox: List<Double> = HANOI,
    /** How far each driver's loop is moved from the recorded one, at most, north-south and east-west. */
    val spreadM: Double = 6_000.0,
    val fixEvery: Duration = 1.seconds,
    val batchSize: Int = 5,
    /** The server's `fleet.tick`, and how far the p95 gap between deltas may stray from it. */
    val expectedGap: Duration = 2.seconds,
    val tolerance: Duration = 500.milliseconds,
) {
    /** One batch per driver every this long: the fixes it holds are this far apart in time. */
    val pace: Duration get() = fixEvery * batchSize

    companion object {
        val HANOI = listOf(105.70, 20.90, 105.98, 21.12)
    }
}

/**
 * Many drivers and dashboards in one JVM (phase 11d, T11.9 and TC-11-LOAD-01). Every driver is a coroutine with its own
 * [DriverClient]; every dashboard opens the admin fleet socket with a ticket and records the time between two
 * `fleet_delta` (or `fleet_cluster`) messages. After [FleetOptions.duration] everything is cancelled and the counts
 * come back as a [FleetReport].
 */
class FleetRun(
    private val http: HttpClient,
    private val options: FleetOptions,
    private val route: List<TrackPoint>,
    private val log: (String) -> Unit = ::println,
) {
    private val stats = FleetStats()
    private val tokens = DevTokens(http, options.baseUrls.first())

    private fun wsBase(n: Int) = options.baseUrls[n % options.baseUrls.size].replaceFirst("http", "ws")

    suspend fun run(): FleetReport {
        coroutineScope {
            val jobs =
                (1..options.drivers).map { n -> launch { drive(n) } } +
                    (1..options.dashboards).map { n -> launch { watch(n) } }
            delay(options.duration)
            jobs.forEach { it.cancel() }
        }
        return FleetReport(options.drivers, options.dashboards, options.duration, stats, options.expectedGap, options.tolerance)
    }

    /** Driver [n]: `sim-driver-NNN`, the same user on every run, sending its loop until cancelled. */
    private suspend fun drive(n: Int) {
        val name = "sim-driver-%03d".format(n)
        val token = tokens.issue("DRIVER", name, stableId(name))
        val loop = FleetLoop.random(route, options.spreadM, options.fixEvery, options.batchSize)
        val sentAt = ConcurrentHashMap<Long, TimeSource.Monotonic.ValueTimeMark>()
        var welcomed = false
        // Drivers start at random moments within one pace, so the batches spread evenly instead of arriving together.
        delay(Random.nextLong(options.pace.inWholeMilliseconds.coerceAtLeast(1)).milliseconds)
        val client =
            DriverClient(
                http,
                "${wsBase(n)}/ws/v1/driver",
                deviceId = name,
                token = token,
                onWelcome = {
                    if (!welcomed) stats.connectedDrivers.incrementAndGet()
                    welcomed = true
                },
                onAck = { ack -> record(ack, sentAt.remove(ack.seq)) },
                onConnectionFailed = { stats.connectionFailures.incrementAndGet() },
            )
        client.hooks =
            object : BatchHooks {
                override fun statusBefore(batch: FixBatch) = null

                override fun pauseBefore(batch: FixBatch) = Duration.ZERO

                override fun sending(batch: FixBatch) {
                    sentAt[batch.seq] = TimeSource.Monotonic.markNow()
                }
            }
        client.source = {
            loop.next(
                kotlin.time.Clock.System
                    .now(),
            )
        }
        client.sendAll(options.pace, maxFailures = Int.MAX_VALUE)
    }

    private fun record(
        ack: Ack,
        sent: TimeSource.Monotonic.ValueTimeMark?,
    ) {
        stats.batches.incrementAndGet()
        stats.accepted.addAndGet(ack.accepted)
        ack.rejected.forEach { stats.rejected(it.reason) }
        sent?.let { stats.ackLatencies += it.elapsedNow() }
    }

    /** Dashboard [n]: a ticket, the fleet socket over the whole area, and the gap between each two deltas. Reconnects until cancelled. */
    @Suppress("TooGenericExceptionCaught") // a dropped socket or a refused ticket: wait and open a new one
    private suspend fun watch(n: Int) {
        val token = tokens.issue("OPERATOR", "sim-dashboard-%02d".format(n), stableId("sim-dashboard-$n"))
        while (true) {
            try {
                val ticket = tokens.ticket(token)
                http.webSocket("${wsBase(n)}/ws/v1/admin/fleet?ticket=$ticket") {
                    stats.connectedDashboards.incrementAndGet()
                    val subscribe = FleetSubscribe(options.bbox, FleetFilters())
                    send(Frame.Text(ProtocolJson.encodeToString(FleetClientMessage.serializer(), subscribe)))
                    var last: TimeSource.Monotonic.ValueTimeMark? = null
                    for (frame in incoming) {
                        val type = (frame as? Frame.Text)?.readText()?.typeOf() ?: continue
                        if (type == "fleet_delta" || type == "fleet_cluster") {
                            if (type == "fleet_delta") stats.deltas.incrementAndGet() else stats.clusters.incrementAndGet()
                            last?.let { stats.deltaGaps += it.elapsedNow() }
                            last = TimeSource.Monotonic.markNow()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Dashboard $n: ${e.message}; connecting again")
            }
            delay(RECONNECT_DELAY)
        }
    }

    private fun String.typeOf(): String? =
        runCatching {
            ProtocolJson
                .parseToJsonElement(this)
                .jsonObject["type"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()

    companion object {
        private val RECONNECT_DELAY = 1.seconds

        /** The same user id for the same name on every run, so the dev token endpoint updates the user instead of adding one. */
        fun stableId(name: String): String = UUID.nameUUIDFromBytes("rts-simulator:$name".toByteArray()).toString()

        /** A CIO client that may hold [sockets] WebSockets to one server; CIO's defaults stop at 100 per host. */
        fun httpClient(sockets: Int): HttpClient =
            HttpClient(CIO) {
                engine {
                    maxConnectionsCount = sockets + SPARE_CONNECTIONS
                    endpoint { maxConnectionsPerRoute = sockets + SPARE_CONNECTIONS }
                }
                install(WebSockets)
            }

        private const val SPARE_CONNECTIONS = 50
    }
}

/** The dev token endpoint and the WebSocket tickets, with a wait and a retry when the server answers 429. */
class DevTokens(
    private val http: HttpClient,
    private val baseUrl: String,
) {
    suspend fun issue(
        role: String,
        name: String,
        userId: String,
    ): String =
        field(
            "token",
            "$baseUrl/api/v1/dev/tokens",
            null,
            """{"role":"$role","displayName":"$name","userId":"$userId"}""",
        )

    suspend fun ticket(token: String): String = field("ticket", "$baseUrl/api/v1/admin/ws-tickets", token, null)

    private suspend fun field(
        name: String,
        url: String,
        token: String?,
        body: String?,
    ): String {
        repeat(ATTEMPTS) {
            val response =
                http.post(url) {
                    token?.let(::bearerAuth)
                    body?.let {
                        contentType(ContentType.Application.Json)
                        setBody(it)
                    }
                }
            if (response.status.isSuccess()) {
                return ProtocolJson
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue(name)
                    .jsonPrimitive.content
            }
            check(response.status == HttpStatusCode.TooManyRequests) { "$url: ${response.status} ${response.bodyAsText()}" }
            delay(response.headers["Retry-After"]?.toLongOrNull()?.seconds ?: RETRY_DELAY)
        }
        error("$url: still 429 after $ATTEMPTS attempts; raise HTTP_RATELIMITPERMINUTE on the server")
    }

    private companion object {
        const val ATTEMPTS = 10
        val RETRY_DELAY = 5.seconds
    }
}
