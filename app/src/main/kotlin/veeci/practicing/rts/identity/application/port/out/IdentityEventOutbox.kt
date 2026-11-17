package veeci.practicing.rts.identity.application.port.out

import veeci.practicing.rts.identity.domain.event.DriverAccountChanged

/** Stores the identity context's events for delivery after the commit. Joins the caller's transaction. */
interface IdentityEventOutbox {
    suspend fun add(event: DriverAccountChanged)
}
