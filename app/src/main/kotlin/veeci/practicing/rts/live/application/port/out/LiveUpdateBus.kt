package veeci.practicing.rts.live.application.port.out

import kotlinx.coroutines.flow.Flow
import veeci.practicing.rts.live.application.api.LiveUpdate
import veeci.practicing.rts.live.application.api.LiveUpdates
import java.util.UUID

/**
 * Fans updates out to everyone who watches a trip. The in-memory version works within one server; phase 10 adds
 * a Redis version so that a guest connected to instance A sees updates published on instance B.
 */
interface LiveUpdateBus : LiveUpdates {
    /** The updates of [tripId] from now on, starting with the latest one published before, if any. */
    fun subscribe(tripId: UUID): Flow<LiveUpdate>

    /** How many collectors watch [tripId] on this instance right now. */
    fun subscriberCount(tripId: UUID): Int

    /** Whether anyone watches [tripId], on this instance or another one (phase 10). */
    suspend fun isWatched(tripId: UUID): Boolean

    /** How many trips the bus keeps state for; finished trips without watchers are dropped. */
    val tripCount: Int
}
