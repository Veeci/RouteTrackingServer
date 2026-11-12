package veeci.practicing.rts.tracking.application.api

import veeci.practicing.rts.shared.UserId
import kotlin.time.Instant

/**
 * When the server last heard from drivers: the tracking context's read API for presence (phase 4). Every
 * stored batch and every `device_status` moves a session's last-seen time; this answers with the latest one
 * over each driver's sessions. It reads the database, so every instance sees the same answer.
 */
interface DriverPresence {
    /** Drivers with no session are missing from the map. Joins the caller's transaction when there is one. */
    suspend fun lastSeen(drivers: Collection<UserId>): Map<UserId, Instant>
}
