package veeci.practicing.rts.tracking.application.port.out

import veeci.practicing.rts.tracking.domain.event.FixesAccepted

/**
 * Records events for delivery after the transaction commits. An implementation must write in the caller's
 * transaction, so that an event exists if and only if the change that caused it was committed.
 */
interface TrackingEventOutbox {
    suspend fun add(event: FixesAccepted)
}
