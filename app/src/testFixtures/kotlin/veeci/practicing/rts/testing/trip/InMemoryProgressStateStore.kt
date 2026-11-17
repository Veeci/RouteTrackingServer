package veeci.practicing.rts.testing.trip

import veeci.practicing.rts.trip.application.port.out.ProgressState
import veeci.practicing.rts.trip.application.port.out.ProgressStateStore
import veeci.practicing.rts.trip.domain.TripId
import java.util.concurrent.ConcurrentHashMap

/** Progress state in memory, for tests. The app uses ExposedProgressStateStore (phase 10). */
class InMemoryProgressStateStore : ProgressStateStore {
    private val states = ConcurrentHashMap<TripId, ProgressState>()

    val size: Int get() = states.size

    override suspend fun get(trip: TripId): ProgressState? = states[trip]

    override suspend fun put(
        trip: TripId,
        state: ProgressState,
    ) {
        states[trip] = state
    }

    override suspend fun remove(trip: TripId) {
        states.remove(trip)
    }
}
