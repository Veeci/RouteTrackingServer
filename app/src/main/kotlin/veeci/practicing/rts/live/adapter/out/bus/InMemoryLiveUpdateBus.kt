package veeci.practicing.rts.live.adapter.out.bus

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import veeci.practicing.rts.live.application.api.LiveUpdate
import veeci.practicing.rts.live.application.api.TripStatusUpdate
import veeci.practicing.rts.live.application.port.out.LiveUpdateBus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One hot flow per trip, in this process's memory.
 * - `replay = 1`: a guest who subscribes late gets the latest update at once.
 * - a buffer of 64 with DROP_OLDEST: [publish] never waits; a guest who reads too slowly skips old updates.
 *
 * A trip's flow is created by the first publish or subscribe, and dropped when the trip is finished and nobody
 * watches it any more.
 */
class InMemoryLiveUpdateBus : LiveUpdateBus {
    private class TripFlow {
        val updates = MutableSharedFlow<LiveUpdate>(replay = 1, extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        @Volatile var finished = false
    }

    private val trips = ConcurrentHashMap<UUID, TripFlow>()

    override val tripCount: Int get() = trips.size

    override fun publish(update: LiveUpdate) {
        val trip = trips.computeIfAbsent(update.tripId) { TripFlow() }
        trip.updates.tryEmit(update) // always succeeds with DROP_OLDEST
        if (update is TripStatusUpdate && update.status.isTerminal) {
            trip.finished = true
            dropIfUnused(update.tripId, trip)
        }
    }

    override fun subscribe(tripId: UUID): Flow<LiveUpdate> =
        flow {
            val trip = trips.computeIfAbsent(tripId) { TripFlow() }
            try {
                emitAll(trip.updates)
            } finally {
                dropIfUnused(tripId, trip) // the collector has left the shared flow by now
            }
        }

    override fun subscriberCount(tripId: UUID): Int = trips[tripId]?.updates?.subscriptionCount?.value ?: 0

    private fun dropIfUnused(
        tripId: UUID,
        trip: TripFlow,
    ) {
        if (trip.finished && trip.updates.subscriptionCount.value == 0) trips.remove(tripId, trip)
    }

    private companion object {
        const val BUFFER = 64
    }
}
