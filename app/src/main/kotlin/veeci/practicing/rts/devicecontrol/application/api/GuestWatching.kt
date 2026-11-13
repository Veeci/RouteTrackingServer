package veeci.practicing.rts.devicecontrol.application.api

import veeci.practicing.rts.shared.UserId

/**
 * Device control's public API for the live context: whether a guest watches the driver's trip on this instance.
 * The device then tracks more precisely while someone watches. Never throws: a lost signal only leaves the
 * device's tracking setting as it was.
 */
interface GuestWatching {
    /** The first guest started watching a trip of [driver]. */
    suspend fun firstGuestWatching(driver: UserId)

    /** The last guest stopped watching. [rideStarted]: the trip is IN_PROGRESS, so precision stays. */
    suspend fun lastGuestLeft(
        driver: UserId,
        rideStarted: Boolean,
    )
}
