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

    /** How many collectors watch [tripId] right now. */
    fun subscriberCount(tripId: UUID): Int

    /** How many trips the bus keeps state for; finished trips without watchers are dropped. */
    val tripCount: Int
}
