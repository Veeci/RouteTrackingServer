package veeci.practicing.rts.simulator.routes

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import veeci.practicing.rts.simulator.fleet.Spread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** `simulator routes` (TC-6-PERF-01). */
data class RouteLoadOptions(
    val baseUrl: String = "http://localhost:8080",
    val token: String,
    val rps: Int = 50,
    val duration: Duration = 5.minutes,
    /** minLng, minLat, maxLng, maxLat. Inside the Hanoi extract with a margin, so few points miss a road. */
    val bbox: List<Double> = HANOI_INNER_BOX,
    val p95Limit: Duration = 150.milliseconds,
    val seed: Long = System.nanoTime(),
) {
    companion object {
        val HANOI_INNER_BOX: List<Double> = listOf(105.78, 20.97, 105.88, 21.07)
    }
}

/**
 * Sends `GET /api/v1/routes` between random points of [RouteLoadOptions.bbox] at a fixed rate.
 *
 * It is an open model: request `i` starts at `start + i / rps` whether or not earlier ones have answered. A closed
 * loop (send, wait, send) would slow down with the server and hide exactly the queueing a load test is for.
 */
class RouteLoad(
    private val http: HttpClient,
    private val options: RouteLoadOptions,
) {
    private val random = Random(options.seed)

    suspend fun run(): RouteLoadReport {
        val interval = 1.seconds / options.rps
        val total = (options.duration / interval).toInt()
        val report = RouteLoadReport(options.p95Limit)
        val start = TimeSource.Monotonic.markNow()
        coroutineScope {
            for (i in 0 until total) {
                delay(interval * i - start.elapsedNow())
                val url = "${options.baseUrl}/api/v1/routes?from=${point()}&to=${point()}"
                launch { send(url, report) }
            }
        }
        return report
    }

    @Suppress("TooGenericExceptionCaught") // any failure to get an answer is an error of the run
    private suspend fun send(
        url: String,
        report: RouteLoadReport,
    ) {
        val sent = TimeSource.Monotonic.markNow()
        try {
            val response = http.get(url) { bearerAuth(options.token) }
            val took = sent.elapsedNow()
            when {
                response.status == HttpStatusCode.OK -> report.ok(took)
                response.status == HttpStatusCode.UnprocessableEntity -> report.unroutable(code(response.bodyAsText()))
                else -> report.error("HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            report.error(e::class.simpleName ?: "exception")
        }
    }

    private fun point(): String {
        val box = options.bbox
        return "%.6f,%.6f".format(random.nextDouble(box[MIN_LAT], box[MAX_LAT]), random.nextDouble(box[MIN_LNG], box[MAX_LNG]))
    }

    private fun code(body: String): String = CODE.find(body)?.groupValues?.get(1) ?: "422"

    companion object {
        private const val MIN_LNG = 0
        private const val MIN_LAT = 1
        private const val MAX_LNG = 2
        private const val MAX_LAT = 3
        private val CODE = Regex(""""code"\s*:\s*"([A-Z_]+)"""")
        private const val REQUEST_TIMEOUT_MS = 5_000L
        private const val CONNECTIONS_PER_RPS = 4

        /** Enough connections that a slow server shows up as latency, not as requests waiting for a socket. */
        fun httpClient(rps: Int): HttpClient =
            HttpClient(CIO) {
                engine {
                    requestTimeout = REQUEST_TIMEOUT_MS
                    maxConnectionsCount = rps * CONNECTIONS_PER_RPS
                    endpoint { maxConnectionsPerRoute = rps * CONNECTIONS_PER_RPS }
                }
            }
    }
}

/**
 * What a run counted. Percentiles are over the 200 answers only. A 422 is the server's correct answer for a random
 * point in a lake or a park (NO_ROAD_NEARBY), so it is counted apart and is not an error. Every other answer, a
 * timeout or a refused connection is an error.
 */
class RouteLoadReport(
    private val p95Limit: Duration,
) {
    private val latencies = ConcurrentLinkedQueue<Duration>()
    private val unroutable = ConcurrentHashMap<String, AtomicInteger>()
    private val errors = ConcurrentHashMap<String, AtomicInteger>()

    fun ok(took: Duration) {
        latencies += took
    }

    fun unroutable(code: String) {
        unroutable.computeIfAbsent(code) { AtomicInteger() }.incrementAndGet()
    }

    fun error(kind: String) {
        errors.computeIfAbsent(kind) { AtomicInteger() }.incrementAndGet()
    }

    val spread: Spread get() = Spread.of(latencies)

    val errorCount: Int get() = errors.values.sumOf { it.get() }

    /** TC-6-PERF-01: some routes answered, none failed, and p95 under the limit. */
    val passed: Boolean get() = errorCount == 0 && spread.count > 0 && spread.p95 < p95Limit

    fun lines(): List<String> =
        listOf(
            "Routes: ${spread.count} answered 200: $spread",
            "Unroutable (422, not errors): ${unroutable.mapValues { it.value.get() }.ifEmpty { "none" }}",
            "Errors: $errorCount ${errors.mapValues { it.value.get() }.ifEmpty { "" }}",
            if (passed) "PASS: no errors and p95 under $p95Limit" else "FAIL: TC-6-PERF-01 needs no errors and p95 under $p95Limit",
        )
}
