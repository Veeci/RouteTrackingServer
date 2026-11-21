package veeci.practicing.rts.fleet.application.port.out

import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetCounts
import veeci.practicing.rts.fleet.domain.FleetCursor
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.fleet.domain.FleetFilter
import veeci.practicing.rts.fleet.domain.MapCell
import java.util.UUID
import kotlin.time.Instant

/**
 * The `fleet_drivers` table (V20). Runs inside the caller's transaction. "Online" reads `heard_at` (V23), which
 * [FleetPresenceRepository] writes; nothing here writes it.
 */
interface FleetRepository {
    suspend fun find(driverId: UUID): FleetDriver?

    /**
     * Reads the driver's row and locks it until the transaction ends, so two writers of one driver take turns. A
     * driver with no row reads as an empty [FleetDriver]. Writes the whole row that [change] returns, with
     * [updatedAt] as the server time of the write. Null writes nothing.
     */
    suspend fun update(
        driverId: UUID,
        updatedAt: Instant,
        change: (FleetDriver) -> FleetDriver?,
    )

    /**
     * Gives each driver of [positions] that position when it is newer than the stored one, or when none is stored,
     * and creates the row of a driver who has none. Newer wins inside the statement itself, so nothing is read or
     * locked first: one statement per driver (fleet lag fix A1). A position that is not newer writes nothing, not
     * even [updatedAt]. `last_seen_at` becomes the newer of the position time and the stored status time.
     */
    suspend fun savePositions(
        positions: Map<UUID, DriverPosition>,
        updatedAt: Instant,
    )

    /** [savePositions] for device statuses: the same rule on the status columns and `status_at`. */
    suspend fun saveDevices(
        devices: Map<UUID, DeviceState>,
        updatedAt: Instant,
    )

    /**
     * Drivers matching [filter], in [FleetCursor] order, at most [limit], after [after]. A driver counts as online
     * when heard after [onlineSince].
     */
    suspend fun page(
        filter: FleetFilter,
        onlineSince: Instant,
        after: FleetCursor?,
        limit: Int,
    ): List<FleetDriver>

    suspend fun counts(onlineSince: Instant): FleetCounts

    /**
     * The live map's tick (11c): every row written after [since], and every row whose `heard_at` lies between
     * [staleFrom] and [staleTo]. The second part finds the drivers who went offline: going offline is only time
     * passing, it writes no row. Coming back online does write one: the presence flush moves `updated_at` with
     * `heard_at`.
     */
    suspend fun changedSince(
        since: Instant,
        staleFrom: Instant,
        staleTo: Instant,
    ): List<FleetDriver>

    /** Drivers matching [filter] counted per grid cell of [cellDeg] degrees, for a map area with too many markers. */
    suspend fun cells(
        filter: FleetFilter,
        onlineSince: Instant,
        cellDeg: Double,
    ): List<MapCell>

    /**
     * Empties the table (TRUNCATE). It locks the table until the transaction ends: other writers wait, and readers too.
     */
    suspend fun clear()

    suspend fun insertAll(
        drivers: Collection<FleetDriver>,
        updatedAt: Instant,
    )
}
