package veeci.practicing.rts.fleet.application.port.out

import veeci.practicing.rts.fleet.domain.FleetCounts
import veeci.practicing.rts.fleet.domain.FleetCursor
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.fleet.domain.FleetFilter
import java.util.UUID
import kotlin.time.Instant

/** The `fleet_drivers` table (V20). Runs inside the caller's transaction. */
interface FleetRepository {
    suspend fun find(driverId: UUID): FleetDriver?

    /** Reads the row and locks it until the transaction ends, so two writers of one driver take turns. */
    suspend fun findForUpdate(driverId: UUID): FleetDriver?

    /** Inserts or replaces the row; [updatedAt] is the server time of the write. */
    suspend fun save(
        driver: FleetDriver,
        updatedAt: Instant,
    )

    /**
     * Drivers matching [filter], in [FleetCursor] order, at most [limit], after [after]. A driver counts as online
     * when last seen after [onlineSince].
     */
    suspend fun page(
        filter: FleetFilter,
        onlineSince: Instant,
        after: FleetCursor?,
        limit: Int,
    ): List<FleetDriver>

    suspend fun counts(onlineSince: Instant): FleetCounts

    /**
     * Empties the table (TRUNCATE). It locks the table until the transaction ends: other writers wait, and readers too.
     */
    suspend fun clear()

    suspend fun insertAll(
        drivers: Collection<FleetDriver>,
        updatedAt: Instant,
    )
}
