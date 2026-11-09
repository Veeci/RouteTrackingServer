package veeci.practicing.rts.trip.adapter.out.memory

import veeci.practicing.rts.trip.application.port.out.ProgressState
import veeci.practicing.rts.trip.application.port.out.ProgressStateStore
import veeci.practicing.rts.trip.domain.TripId
import java.util.concurrent.ConcurrentHashMap

/** Progress state in this process's memory (one instance). Phase 10 moves it to Redis for several instances. */
class InMemoryProgressStateStore : ProgressStateStore {
    private val states = ConcurrentHashMap<TripId, ProgressState>()

    val size: Int get() = states.size

    override fun get(trip: TripId): ProgressState? = states[trip]

    override fun put(
        trip: TripId,
        state: ProgressState,
    ) {
        states[trip] = state
    }

    override fun remove(trip: TripId) {
        states.remove(trip)
    }
}
