package veeci.practicing.rts.platform.jobs

import kotlinx.coroutines.CoroutineScope

/**
 * A coroutine that runs for the whole life of the application and works off an in-process queue, for example the
 * re-route worker (phase 7b). The application starts every one it finds in Koin. Graceful shutdown stops them
 * before the connection pool closes, after the periodic jobs and before the outbox relay.
 */
interface BackgroundWorker {
    fun start(scope: CoroutineScope)

    /** Stops the worker and waits until the item in progress has finished or was cancelled. */
    suspend fun stop()
}
