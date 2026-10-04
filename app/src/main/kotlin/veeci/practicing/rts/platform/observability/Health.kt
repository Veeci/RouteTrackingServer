package veeci.practicing.rts.platform.observability

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

enum class HealthStatus { UP, DOWN }

/** Something the app needs in order to serve traffic: the database now, more dependencies later. */
interface HealthIndicator {
    val name: String

    /** May block (e.g. on a socket); [HealthRegistry] runs it on the IO pool under a deadline. */
    fun check(): HealthStatus
}

@Serializable
data class HealthReport(
    val status: HealthStatus,
    val checks: Map<String, HealthStatus> = emptyMap(),
)

/**
 * Answers "can this instance take traffic?" by running every [HealthIndicator] in parallel. A check that
 * doesn't answer within [timeout] counts as DOWN: a probe must answer fast even when a dependency hangs.
 */
class HealthRegistry(
    private val indicators: List<HealthIndicator>,
) : AutoCloseable {
    private val timeout = 2.seconds

    // Checks run here, not in the request's coroutine, so a check stuck in a blocking call can't hold the
    // probe response past the deadline; it finishes (or fails) on its own in the background.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var stopping = false

    /** Shutdown has begun: report DOWN so load balancers stop sending new requests here. */
    fun markStopping() {
        stopping = true
    }

    suspend fun readiness(): HealthReport {
        val running = indicators.map { it to scope.async { runCatching { it.check() }.getOrDefault(HealthStatus.DOWN) } }
        withTimeoutOrNull(timeout) { running.forEach { (_, check) -> check.join() } }
        val checks =
            running.associate { (indicator, check) ->
                indicator.name to if (check.isCompleted) check.await() else HealthStatus.DOWN
            }
        val allUp = !stopping && checks.values.all { it == HealthStatus.UP }
        return HealthReport(if (allUp) HealthStatus.UP else HealthStatus.DOWN, checks)
    }

    override fun close() = scope.cancel()
}

/**
 * Liveness: is the process able to answer at all? Checks nothing else, so a database outage never gets the
 * process restarted (a restart can't fix the database). Readiness: should traffic be routed here right now?
 */
fun Route.healthRoutes(registry: HealthRegistry) {
    get("/health/live") { call.respond(HealthReport(HealthStatus.UP)) }

    get("/health/ready") {
        val report = registry.readiness()
        val status = if (report.status == HealthStatus.UP) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, report)
    }
}
