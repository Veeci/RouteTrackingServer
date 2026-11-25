package veeci.practicing.rts.fleet.application.port.out

import java.util.UUID
import kotlin.time.Instant

/** The drivers that [FleetPresenceRepository.saveHeard] did not write, each with the time it still has to write. */
data class HeardLeft(
    /** Rows that another transaction held, such as the outbox relay in the middle of a batch. */
    val busy: Map<UUID, Instant> = emptyMap(),
    /** Drivers with no row yet. */
    val missing: Map<UUID, Instant> = emptyMap(),
)

/**
 * The `heard_at` column of `fleet_drivers` (V23, fleet lag fix C), apart from [FleetRepository] because the ingest path
 * writes it, not the relay. Runs inside the caller's transaction.
 */
interface FleetPresenceRepository {
    /**
     * Moves `heard_at` to the given time for each driver of [heard] when it is later than the stored one, and sets
     * `updated_at` to [updatedAt] on each row it writes, so the live map's tick sees a driver come back online.
     *
     * It never waits for a row lock. A row that another transaction holds is skipped and returned as busy. The relay
     * locks many rows in one transaction in event order, so waiting here while holding other rows could deadlock with
     * it, and a deadlock would roll back the relay's batch.
     */
    suspend fun saveHeard(
        heard: Map<UUID, Instant>,
        updatedAt: Instant,
    ): HeardLeft

    /**
     * Creates the row of [driverId] with only `heard_at` set, or, when another writer created the row first, moves its
     * `heard_at` forward (newer wins). It may wait for that writer's commit, so call it in a transaction that holds no
     * other row of the table.
     */
    suspend fun create(
        driverId: UUID,
        heardAt: Instant,
        updatedAt: Instant,
    )
}
