package veeci.practicing.rts.trip.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.jobs.BackgroundWorker

/**
 * Runs re-routes one at a time, away from the outbox relay.
 *
 * Progress runs inside the relay's transaction, and the relay delivers one event at a time. Planning there would
 * hold a connection and delay every other event for up to the planner's 500 ms budget. So progress only queues a
 * request ([request] never waits) and this worker plans and stores it on its own.
 *
 * The queue lives in memory. A request lost in a restart does no harm: the driver is still off the route, so the
 * rule fires again three fixes later. When the queue is full the oldest request goes: it is the most out of date.
 */
class RerouteWorker(
    private val handler: RerouteHandler,
    capacity: Int = CAPACITY,
) : Rerouter,
    BackgroundWorker {
    private val log = LoggerFactory.getLogger(RerouteWorker::class.java)
    private val requests = Channel<RerouteRequest>(capacity, BufferOverflow.DROP_OLDEST)

    @Volatile
    private var job: Job? = null

    override fun request(request: RerouteRequest): Boolean = requests.trySend(request).isSuccess

    override fun start(scope: CoroutineScope) {
        job = scope.launch(CoroutineName("reroute")) { for (request in requests) handle(request) }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
    }

    @Suppress("TooGenericExceptionCaught") // one failed re-route must not stop the worker
    private suspend fun handle(request: RerouteRequest) {
        try {
            val outcome = handler.reroute(request)
            log.info("Re-route of trip {} from version {}: {}", request.tripId.value, request.baseVersion, outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Re-route of trip {} failed", request.tripId.value, e)
        }
    }

    private companion object {
        const val CAPACITY = 64
    }
}
