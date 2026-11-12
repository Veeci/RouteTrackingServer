package veeci.practicing.rts.trip.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What the presence rule remembers for one trip. Stored, so a restart cannot raise a second DriverStale. */
data class PresenceState(
    /** DriverStale was raised and DriverBack not yet. */
    val stale: Boolean = false,
)

/** The new state, and what to raise: the last-seen time for DriverStale, or [back] for DriverBack. */
data class PresenceOutcome(
    val state: PresenceState,
    val staleSince: Instant? = null,
    val back: Boolean = false,
)

/**
 * Decides when the driver counts as gone and when they are back. Pure. Stale and back come in pairs: never
 * two DriverStale in a row. The stale-driver job calls it every few seconds, so "back" shows within one period.
 */
class PresenceRules(
    /** Silence longer than this is stale: two missed `device_status` heartbeats (they come every 30 s). */
    private val staleAfter: Duration = 60.seconds,
) {
    fun evaluate(
        state: PresenceState,
        lastSeen: Instant,
        now: Instant,
    ): PresenceOutcome {
        val silent = now - lastSeen > staleAfter
        return when {
            silent && !state.stale -> PresenceOutcome(PresenceState(stale = true), staleSince = lastSeen)
            !silent && state.stale -> PresenceOutcome(PresenceState(stale = false), back = true)
            else -> PresenceOutcome(state)
        }
    }
}
