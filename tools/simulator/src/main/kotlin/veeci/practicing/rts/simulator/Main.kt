package veeci.practicing.rts.simulator

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.protocol.CommandAck
import veeci.practicing.rts.protocol.DeviceDto
import veeci.practicing.rts.protocol.DriverPosition
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.GuestServerMessage
import veeci.practicing.rts.protocol.LocationProviderDto
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.RouteUpdated
import veeci.practicing.rts.protocol.Subscribed
import veeci.practicing.rts.protocol.TripEvent
import veeci.practicing.rts.protocol.TripProgress
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Instant

private const val USAGE = """Usage: simulator <command> [options]

simulator token --role GUEST|DRIVER|ADMIN [--name NAME] [--url URL]
  Creates a user and prints an access token for it (dev and test servers only).
  --url URL                 server                 (default http://localhost:8080)

simulator driver --token TOKEN [options]
  Replays a recorded route against the driver endpoint, like a phone that drives it.
  --url URL                 driver endpoint        (default ws://localhost:8080/ws/v1/driver)
  --gpx FILE                GPX file or resource   (default routes/city_loop.gpx)
  --batch-size N            fixes per batch        (default 10)
  --speedup N               replay N times faster  (default 10)
  --disconnect-every N      reconnect after N acks (default 0 = never)
  --device ID               device id              (default dev-sim-1)
  --battery N               battery at the start   (default 80); a device_status every 30 s of route time
  --battery-drain P         percent lost per minute of route time (default 0); try --battery 20 --battery-drain 2
  --pause-at S:D            after S seconds of route, send nothing for D real seconds (default none);
                            with the server's 60 s stale time, 120:70 shows driver_stale, then driver_back
  --reject-commands true    answer every server command with rejected (default: applied)
  --detour-at S:D:M         after S seconds of route, drive M metres east of it for D seconds (default none);
                            during a ride, 60:60:150 makes the server see the driver off-route and re-route
  --provider P              provider of every fix (default GMS_FUSED); also AOSP_GPS, AOSP_NETWORK, AOSP_FUSED
  --manufacturer M          device block of hello  (default Google)
  --model M                                        (default Pixel 7)
  --android-sdk N                                  (default 35)
  --rom R                                          (default none, a stock ROM)
  --has-gms true|false                             (default true)
  --motion M                motion in each status  (default IN_VEHICLE)
  --doze-gap S:D            after S seconds of route, the phone lies still for D seconds and sends nothing;
                            later fixes move D later, and statuses near the gap say STILL (default none)

simulator guest --token TOKEN --trip TRIP_ID [--url URL]
  Watches a trip live and prints every position, progress update and status change.
  --url URL                 guest endpoint         (default ws://localhost:8080/ws/v1/guest)"""

/** Entry point of the simulator CLI: `token`, `driver` or `guest`. */
fun main(args: Array<String>) {
    val options =
        args
            .drop(1)
            .chunked(2)
            .filter { it.size == 2 }
            .associate { (key, value) -> key.removePrefix("--") to value }
    val succeeded =
        when (args.firstOrNull()) {
            "token" -> runBlocking { token(options) }
            "driver" -> runBlocking { replay(DriverOptions.of(options)) }
            "guest" -> runBlocking { watch(options) }
            else -> false.also { println(USAGE) }
        }
    exitProcess(if (succeeded) 0 else 1)
}

private suspend fun token(options: Map<String, String>): Boolean {
    val role = options["role"]
    if (role == null) return false.also { println(USAGE) }
    val name = options["name"]?.let { ""","displayName":"$it"""" } ?: ""
    HttpClient(CIO).use { http ->
        val response =
            http.post("${options["url"] ?: "http://localhost:8080"}/api/v1/dev/tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"role":"$role"$name}""")
            }
        if (response.status.isSuccess()) {
            val body = ProtocolJson.parseToJsonElement(response.bodyAsText()).jsonObject
            println("userId: ${body.getValue("userId").jsonPrimitive.content}")
            println("token:  ${body.getValue("token").jsonPrimitive.content}")
        } else {
            println("${response.status}: ${response.bodyAsText()}")
        }
        return response.status.isSuccess()
    }
}

private suspend fun watch(options: Map<String, String>): Boolean {
    val token = options["token"]
    val trip = options["trip"]
    if (token == null || trip == null) return false.also { println(USAGE) }
    HttpClient(CIO) { install(WebSockets) }.use { http ->
        val reason = GuestClient(http, options["url"] ?: "ws://localhost:8080/ws/v1/guest", token).watch(trip) { println(it.describe()) }
        println("The server closed the socket: ${reason?.code} ${reason?.message}")
        return true
    }
}

private fun GuestServerMessage.describe(): String =
    when (this) {
        is Subscribed -> {
            "Watching trip ${trip.tripId}: ${trip.status}"
        }

        is DriverPosition -> {
            "Driver at ${lat.rounded()}, ${lng.rounded()} (${speedMps?.let { "${it.rounded(1)} m/s" } ?: "speed unknown"})"
        }

        is TripProgress -> {
            "${remainingM.toInt()} m to the ${target.lowercase()}, about ${etaS.toInt()} s (${basis.lowercase()})"
        }

        is TripEvent -> {
            describe()
        }

        is RouteUpdated -> {
            "New ${kind.lowercase()} route v$routeVersion: ${distanceM.toInt()} m, about ${durationS.toInt()} s ($reason)"
        }
    }

private fun TripEvent.describe(): String =
    when (event) {
        TripEvent.STATUS_CHANGED -> "Trip is now $status"
        TripEvent.DRIVER_ARRIVING -> "Driver arriving: ${remainingM?.toInt()} m to the pickup"
        TripEvent.DRIVER_LOW_BATTERY -> "Driver's phone battery is low: $levelPercent %"
        TripEvent.DRIVER_STALE -> "Connection to the driver lost (last heard $lastSeenAt)"
        TripEvent.DRIVER_BACK -> "Driver is back online"
        TripEvent.OFF_ROUTE -> "Driver left the route; planning a new one"
        else -> "Trip event $event"
    }

private fun Double.rounded(decimals: Int = 5): String = "%.${decimals}f".format(this)

private class DriverOptions(
    val url: String,
    val gpx: String,
    val batchSize: Int,
    val speedup: Double,
    val disconnectEvery: Int,
    val token: String?,
    val phone: DeviceSettings,
    val detour: Detour?,
    val profile: PhoneProfile,
) {
    companion object {
        fun of(values: Map<String, String>) =
            DriverOptions(
                url = values["url"] ?: "ws://localhost:8080/ws/v1/driver",
                gpx = values["gpx"] ?: "routes/city_loop.gpx",
                batchSize = values["batch-size"]?.toInt() ?: 10,
                speedup = values["speedup"]?.toDouble() ?: 10.0,
                disconnectEvery = values["disconnect-every"]?.toInt() ?: 0,
                token = values["token"],
                phone =
                    DeviceSettings(
                        battery = values["battery"]?.toInt() ?: DeviceScript.DEFAULT_BATTERY,
                        batteryDrain = values["battery-drain"]?.toDouble() ?: 0.0,
                        pause = values["pause-at"]?.let(DeviceScript::parsePause),
                        rejectCommands = values["reject-commands"] == "true",
                    ),
                detour = values["detour-at"]?.let(Detour::parse),
                profile = PhoneProfile.of(values),
            )
    }
}

private suspend fun replay(options: DriverOptions): Boolean {
    val recorded = GpxReader.load(options.gpx)
    // The pace follows the recorded route, before a Doze gap stretches it.
    val interval = (recorded.last().time - recorded.first().time) / (recorded.size - 1)
    val points =
        recorded
            .let { options.detour?.applyTo(it) ?: it }
            .let { options.profile.dozeGap?.applyTo(it) ?: it }
    val fixes = points.toFixes(now = Instant.fromEpochMilliseconds(System.currentTimeMillis()), provider = options.profile.provider)
    val pace = interval * options.batchSize / options.speedup
    val stats = Stats()
    val script =
        DeviceScript(
            routeStart = fixes.first().recordedAt,
            batteryPercent = options.phone.battery,
            drainPerMinute = options.phone.batteryDrain,
            pauseAt = options.phone.pause?.first,
            pauseFor = options.phone.pause?.second ?: Duration.ZERO,
            motion = options.profile.motion,
            dozeGap = options.profile.dozeGap,
        )
    // The script, plus a line on the console for each heartbeat and silence.
    val hooks =
        object : BatchHooks {
            override fun statusBefore(batch: FixBatch) =
                script.statusBefore(batch)?.also { println("Battery ${it.battery.levelPercent} %") }

            override fun pauseBefore(batch: FixBatch) = script.pauseBefore(batch).also { if (it > Duration.ZERO) println("Silent for $it") }

            override fun answer(command: Command): String {
                val answer = if (options.phone.rejectCommands) CommandAck.REJECTED else CommandAck.APPLIED
                println("Command ${command.kind} ${command.maxAccuracy ?: ""} ${command.reason ?: ""}: $answer")
                return answer
            }

            override fun routeUpdated(route: RouteUpdated) =
                println("Route ${route.kind.lowercase()} v${route.routeVersion}: ${route.distanceM.toInt()} m (${route.reason})")
        }
    HttpClient(CIO) { install(WebSockets) }.use { http ->
        val client =
            DriverClient(
                http,
                options.url,
                options.profile.deviceId,
                token = options.token,
                onWelcome = { println("Connected; the server resumes from seq ${it.resumeFromSeq}") },
                onAck = stats::record,
                onConnectionFailed = { println("Connection failed (${it.message}); trying again") },
            ).also {
                it.hooks = hooks
                it.device = options.profile.hardware
            }
        fixes.chunked(options.batchSize).forEach(client::enqueue)
        println("Session ${client.sessionId}: ${fixes.size} fixes in ${client.pending} batches, one batch every $pace")
        val succeeded = client.sendAll(pace, batchesPerConnection = options.disconnectEvery.takeIf { it > 0 } ?: Int.MAX_VALUE)
        stats.print(client.connections)
        return succeeded
    }
}

/** What the phone is and how it locates: `--device`, and (phase 9) `--provider`, the device block, `--motion` and `--doze-gap`. */
private class PhoneProfile(
    /** `--device`, the id the phone sends in hello. */
    val deviceId: String,
    val provider: LocationProviderDto,
    val hardware: DeviceDto,
    val motion: String,
    val dozeGap: DozeGap?,
) {
    companion object {
        private const val DEFAULT_ANDROID_SDK = 35

        fun of(values: Map<String, String>) =
            PhoneProfile(
                deviceId = values["device"] ?: "dev-sim-1",
                provider = values["provider"]?.let(LocationProviderDto::valueOf) ?: LocationProviderDto.GMS_FUSED,
                hardware =
                    DeviceDto(
                        manufacturer = values["manufacturer"] ?: "Google",
                        model = values["model"] ?: "Pixel 7",
                        androidSdk = values["android-sdk"]?.toInt() ?: DEFAULT_ANDROID_SDK,
                        rom = values["rom"],
                        hasGms = values["has-gms"]?.toBooleanStrict() ?: true,
                    ),
                motion = values["motion"] ?: DeviceScript.DEFAULT_MOTION,
                dozeGap = values["doze-gap"]?.let(DozeGap::parse),
            )
    }
}

/** `--battery`, `--battery-drain` and `--pause-at`. */
private class DeviceSettings(
    val battery: Int,
    val batteryDrain: Double,
    val pause: Pair<Duration, Duration>?,
    val rejectCommands: Boolean,
)

private class Stats {
    private var batches = 0
    private var accepted = 0
    private val rejected = mutableMapOf<String, Int>()

    fun record(ack: Ack) {
        batches++
        accepted += ack.accepted
        ack.rejected.forEach { rejected.merge(it.reason, 1, Int::plus) }
        println("seq ${ack.seq}: ${ack.accepted} accepted, ${ack.rejected.size} rejected")
    }

    fun print(connections: Int) {
        println("Done: $batches batches acked over $connections connection(s), $accepted fixes accepted, rejected: $rejected")
    }
}
