package veeci.practicing.rts.fleet.adapter.out.persistence

import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import veeci.practicing.rts.fleet.application.port.out.FleetPresenceRepository
import veeci.practicing.rts.fleet.application.port.out.HeardLeft
import java.util.UUID
import kotlin.time.Instant

/**
 * `heard_at` on `fleet_drivers` (V23). Runs inside the caller's transaction. Drivers go in chunks of [CHUNK], because
 * each one is a bind parameter of an `IN` list and Postgres takes at most 65 535 of them in one statement.
 */
class ExposedFleetPresenceRepository : FleetPresenceRepository {
    private val table = FleetDriversTable

    override suspend fun saveHeard(
        heard: Map<UUID, Instant>,
        updatedAt: Instant,
    ): HeardLeft {
        val busy = mutableMapOf<UUID, Instant>()
        val missing = mutableMapOf<UUID, Instant>()
        heard.keys.chunked(CHUNK).forEach { ids ->
            val present = table.select(table.driverId).where { table.driverId inList ids }.mapTo(HashSet()) { it[table.driverId] }
            // SKIP LOCKED leaves out the rows another transaction holds, instead of waiting for them.
            val free =
                table
                    .select(table.driverId)
                    .where { table.driverId inList present }
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(mode = ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .mapTo(HashSet()) { it[table.driverId] }
            ids.forEach { id ->
                when (id) {
                    !in present -> missing[id] = heard.getValue(id)
                    !in free -> busy[id] = heard.getValue(id)
                }
            }
            write(free.associateWith(heard::getValue), updatedAt)
        }
        return HeardLeft(busy, missing)
    }

    override suspend fun create(
        driverId: UUID,
        heardAt: Instant,
        updatedAt: Instant,
    ) {
        table.upsert(
            table.driverId,
            onUpdate = {
                it[table.heardAt] = insertValue(table.heardAt)
                it[table.updatedAt] = insertValue(table.updatedAt)
            },
            where = { newerThanStored(table.heardAt) },
        ) {
            it[table.driverId] = driverId
            it[table.enabled] = true
            it[table.heardAt] = heardAt
            it[table.updatedAt] = updatedAt
        }
    }

    // Every row here exists and this transaction holds it, so the upsert always takes the ON CONFLICT path and never
    // waits. The WHERE keeps newer wins against another instance's flush.
    private fun write(
        heard: Map<UUID, Instant>,
        updatedAt: Instant,
    ) {
        if (heard.isEmpty()) return
        table.batchUpsert(
            heard.entries,
            table.driverId,
            onUpdate = {
                it[table.heardAt] = insertValue(table.heardAt)
                it[table.updatedAt] = insertValue(table.updatedAt)
            },
            where = { newerThanStored(table.heardAt) },
            shouldReturnGeneratedValues = false,
        ) { (driverId, at) ->
            this[FleetDriversTable.driverId] = driverId
            this[FleetDriversTable.enabled] = true
            this[FleetDriversTable.heardAt] = at
            this[FleetDriversTable.updatedAt] = updatedAt
        }
    }

    private companion object {
        const val CHUNK = 1_000
    }
}
