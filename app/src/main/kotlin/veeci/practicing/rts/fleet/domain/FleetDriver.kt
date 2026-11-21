package veeci.practicing.rts.fleet.domain

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Instant

/** Where a driver was, from their newest accepted fix. [at] is the fix's recorded time (the phone's clock). */
data class DriverPosition(
    val lat: Double,
    val lng: Double,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val at: Instant,
)

/** The phone's battery and motion, from its newest device_status. */
data class DeviceState(
    val batteryPct: Int,
    val isCharging: Boolean,
    val motion: String?,
    val at: Instant,
)

/** The stage of the driver's newest trip. [currentTripId] is null once that trip has ended. */
data class TripState(
    val currentTripId: UUID?,
    val status: String,
    val at: Instant,
)

/**
 * One driver as the fleet view sees them (phase 11): a row of the read model, put together from other contexts'
 * events. Events arrive at least once, and a rebuild replays stored data over the same rules, so every change
 * follows "newer data wins": each `with...` returns the changed driver, or null when the change is not newer than
 * what is stored (a repeated or late event). Null means "write nothing", so a repeated event does not even move the
 * row's update time. Positions and device statuses follow the same rule in SQL, one statement per event
 * (`FleetRepository.savePositions`, fleet lag fix A1), because they are most of the relay's events.
 */
data class FleetDriver(
    val driverId: UUID,
    /** Null until the identity context sends the account; a driver made only from a token has none. */
    val displayName: String? = null,
    val enabled: Boolean = true,
    val position: DriverPosition? = null,
    val device: DeviceState? = null,
    val trip: TripState? = null,
) {
    /** The newer of the position and status times: when the driver's phone last told us something. */
    val lastSeenAt: Instant? get() = listOfNotNull(position?.at, device?.at).maxOrNull()

    val onTrip: Boolean get() = trip?.currentTripId != null

    /** Online: heard from within [staleAfter] before [now]. The same stale time that tells guests a driver is gone. */
    fun isOnline(
        now: Instant,
        staleAfter: Duration,
    ): Boolean = lastSeenAt?.let { it > now - staleAfter } ?: false

    /** The account carries no time: the outbox delivers account events in order, so the last one wins. */
    fun withAccount(
        displayName: String,
        enabled: Boolean,
    ): FleetDriver? = copy(displayName = displayName, enabled = enabled).takeIf { it != this }

    /**
     * Trip [tripId] reached [status] at [at]. An [ended] trip clears the current trip, but only if it is that trip:
     * the end of an older trip never hides the one the driver is on now.
     */
    fun withTripStage(
        tripId: UUID,
        status: String,
        ended: Boolean,
        at: Instant,
    ): FleetDriver? {
        val stored = trip
        val older = stored != null && at <= stored.at
        val endsAnotherTrip = ended && stored?.currentTripId != null && stored.currentTripId != tripId
        return if (older || endsAnotherTrip) null else copy(trip = TripState(if (ended) null else tripId, status, at))
    }
}
