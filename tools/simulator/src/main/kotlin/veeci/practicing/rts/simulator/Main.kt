package veeci.practicing.rts.simulator

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import veeci.practicing.rts.protocol.Ack
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val USAGE = """Usage: simulator driver [options]
  --url URL                 driver endpoint        (default ws://localhost:8080/ws/v1/driver)
  --gpx FILE                GPX file or resource   (default routes/city_loop.gpx)
  --batch-size N            fixes per batch        (default 10)
  --speedup N               replay N times faster  (default 10)
  --disconnect-every N      reconnect after N acks (default 0 = never)
  --device ID               device id              (default dev-sim-1)"""

private const val MAX_FAILED_CONNECTIONS = 5

/** Replays a recorded route against the driver endpoint, like a phone that drives it. */
fun main(args: Array<String>) {
    if (args.firstOrNull() != "driver") {
        println(USAGE)
        exitProcess(2)
    }
    val options = Options.parse(args.drop(1))
    val succeeded = runBlocking { replay(options) }
    exitProcess(if (succeeded) 0 else 1)
}

private class Options(
    val url: String,
    val gpx: String,
    val batchSize: Int,
    val speedup: Double,
    val disconnectEvery: Int,
    val device: String,
) {
    companion object {
        fun parse(args: List<String>): Options {
            val values = args.chunked(2).associate { (key, value) -> key.removePrefix("--") to value }
            return Options(
                url = values["url"] ?: "ws://localhost:8080/ws/v1/driver",
                gpx = values["gpx"] ?: "routes/city_loop.gpx",
                batchSize = values["batch-size"]?.toInt() ?: 10,
                speedup = values["speedup"]?.toDouble() ?: 10.0,
                disconnectEvery = values["disconnect-every"]?.toInt() ?: 0,
                device = values["device"] ?: "dev-sim-1",
            )
        }
    }
}

private suspend fun replay(options: Options): Boolean {
    val points = GpxReader.load(options.gpx)
    val fixes = points.toFixes(now = Instant.fromEpochMilliseconds(System.currentTimeMillis()))
    val interval = (points.last().time - points.first().time) / (points.size - 1)
    val pace = interval * options.batchSize / options.speedup
    val stats = Stats()
    HttpClient(CIO) { install(WebSockets) }.use { http ->
        val client = DriverClient(http, options.url, options.device, onAck = stats::record)
        fixes.chunked(options.batchSize).forEach(client::enqueue)
        println("Session ${client.sessionId}: ${fixes.size} fixes in ${client.pending} batches, one batch every $pace")
        var failures = 0
        while (client.pending > 0 && failures < MAX_FAILED_CONNECTIONS) {
            stats.connections++
            failures = if (connect(client, options, pace)) 0 else failures + 1
        }
        stats.print()
        return client.pending == 0
    }
}

/** One connection. False if it failed; the client keeps its outbox and the next connection resends. */
@Suppress("TooGenericExceptionCaught") // any failure (refused, reset, closed by the server) means: try again
private suspend fun connect(
    client: DriverClient,
    options: Options,
    pace: kotlin.time.Duration,
): Boolean =
    try {
        client.connectAndSend(pace, maxBatches = options.disconnectEvery.takeIf { it > 0 } ?: Int.MAX_VALUE)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Connection failed (${e.message}); retrying in 1 s")
        delay(1.seconds)
        false
    }

private class Stats {
    var connections = 0
    private var batches = 0
    private var accepted = 0
    private val rejected = mutableMapOf<String, Int>()

    fun record(ack: Ack) {
        batches++
        accepted += ack.accepted
        ack.rejected.forEach { rejected.merge(it.reason, 1, Int::plus) }
        println("seq ${ack.seq}: ${ack.accepted} accepted, ${ack.rejected.size} rejected")
    }

    fun print() {
        println("Done: $batches batches acked over $connections connection(s), $accepted fixes accepted, rejected: $rejected")
    }
}
