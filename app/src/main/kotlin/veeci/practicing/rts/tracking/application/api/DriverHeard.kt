package veeci.practicing.rts.tracking.application.api

import veeci.practicing.rts.shared.UserId
import kotlin.time.Instant

/**
 * Told each time ingest accepts data from a driver: a hello, a stored batch or a device_status (fleet lag fix C).
 * [at] is the server time that ingest also writes into the session's last-seen time, so it agrees with
 * [DriverPresence]. The fleet context implements it to judge "online" on the server's clock instead of the phone's.
 *
 * The tracking context calls it inside the ingest transaction, on the hot path of every batch, so an implementation
 * must return at once: no database call and no suspension. A transaction that rolls back after the call has still
 * heard from the driver, so the call stays true.
 */
fun interface DriverHeard {
    fun heard(
        driver: UserId,
        at: Instant,
    )
}
