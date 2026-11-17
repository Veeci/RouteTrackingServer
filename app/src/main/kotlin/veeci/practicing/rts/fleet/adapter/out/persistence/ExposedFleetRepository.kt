package veeci.practicing.rts.fleet.adapter.out.persistence

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.upsert
import veeci.practicing.rts.fleet.application.port.out.FleetRepository
import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetCounts
import veeci.practicing.rts.fleet.domain.FleetCursor
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.fleet.domain.FleetFilter
import veeci.practicing.rts.fleet.domain.TripState
import java.util.UUID
import kotlin.time.Instant

/** The `fleet_drivers` table (migration V20). */
internal object FleetDriversTable : Table("fleet_drivers") {
    val driverId = javaUUID("driver_id")
    val displayName = text("display_name").nullable()
    val enabled = bool("enabled")
    val lastSeenAt = timestamp("last_seen_at").nullable()
    val lat = double("lat").nullable()
    val lng = double("lng").nullable()
    val speedMps = float("speed_mps").nullable()
    val bearingDeg = float("bearing_deg").nullable()
    val batteryPct = short("battery_pct").nullable()
    val isCharging = bool("is_charging").nullable()
    val motion = text("motion").nullable()
    val positionAt = timestamp("position_at").nullable()
    val statusAt = timestamp("status_at").nullable()
    val currentTripId = javaUUID("current_trip_id").nullable()
    val tripStatus = text("trip_status").nullable()
    val tripStatusAt = timestamp("trip_status_at").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(driverId)
}

/**
 * Runs inside the caller's transaction. The list reads `fleet_drivers_last_seen` in order (V20) and stops after one
 * page: keyset paging, so page 50 costs what page 1 costs.
 */
class ExposedFleetRepository : FleetRepository {
    private val table = FleetDriversTable

    override suspend fun find(driverId: UUID): FleetDriver? =
        table
            .selectAll()
            .where { table.driverId eq driverId }
            .singleOrNull()
            ?.toDriver()

    override suspend fun findForUpdate(driverId: UUID): FleetDriver? =
        table
            .selectAll()
            .where { table.driverId eq driverId }
            .forUpdate()
            .singleOrNull()
            ?.toDriver()

    override suspend fun save(
        driver: FleetDriver,
        updatedAt: Instant,
    ) {
        table.upsert { it.fill(driver, updatedAt) }
    }

    override suspend fun page(
        filter: FleetFilter,
        onlineSince: Instant,
        after: FleetCursor?,
        limit: Int,
    ): List<FleetDriver> =
        table
            .selectAll()
            .where { FleetConditions.matching(filter, onlineSince) and FleetConditions.after(after) }
            .orderBy(table.lastSeenAt to SortOrder.DESC_NULLS_LAST, table.driverId to SortOrder.ASC)
            .limit(limit)
            .map { it.toDriver() }

    override suspend fun counts(onlineSince: Instant): FleetCounts =
        FleetCounts(
            online = count(table.lastSeenAt greater onlineSince),
            onTrip = count(table.currentTripId.isNotNull()),
            disabled = count(table.enabled eq false),
        )

    override suspend fun clear() {
        TransactionManager.current().exec("TRUNCATE fleet_drivers")
    }

    override suspend fun insertAll(
        drivers: Collection<FleetDriver>,
        updatedAt: Instant,
    ) {
        table.batchInsert(drivers, shouldReturnGeneratedValues = false) { fill(it, updatedAt) }
    }

    private fun count(condition: Op<Boolean>): Int =
        table
            .selectAll()
            .where { condition }
            .count()
            .toInt()

    private fun UpdateBuilder<*>.fill(
        driver: FleetDriver,
        updatedAt: Instant,
    ) {
        this[table.driverId] = driver.driverId
        this[table.displayName] = driver.displayName
        this[table.enabled] = driver.enabled
        this[table.lastSeenAt] = driver.lastSeenAt
        this[table.lat] = driver.position?.lat
        this[table.lng] = driver.position?.lng
        this[table.speedMps] = driver.position?.speedMps?.toFloat()
        this[table.bearingDeg] = driver.position?.bearingDeg?.toFloat()
        this[table.positionAt] = driver.position?.at
        this[table.batteryPct] = driver.device?.batteryPct?.toShort()
        this[table.isCharging] = driver.device?.isCharging
        this[table.motion] = driver.device?.motion
        this[table.statusAt] = driver.device?.at
        this[table.currentTripId] = driver.trip?.currentTripId
        this[table.tripStatus] = driver.trip?.status
        this[table.tripStatusAt] = driver.trip?.at
        this[table.updatedAt] = updatedAt
    }

    private fun ResultRow.toDriver(): FleetDriver {
        val positionAt = this[table.positionAt]
        val statusAt = this[table.statusAt]
        val tripAt = this[table.tripStatusAt]
        return FleetDriver(
            driverId = this[table.driverId],
            displayName = this[table.displayName],
            enabled = this[table.enabled],
            position =
                positionAt?.let {
                    DriverPosition(
                        checkNotNull(this[table.lat]),
                        checkNotNull(this[table.lng]),
                        this[table.speedMps]?.decimal(),
                        this[table.bearingDeg]?.decimal(),
                        it,
                    )
                },
            device =
                statusAt?.let {
                    DeviceState(checkNotNull(this[table.batteryPct]).toInt(), checkNotNull(this[table.isCharging]), this[table.motion], it)
                },
            trip = tripAt?.let { TripState(this[table.currentTripId], checkNotNull(this[table.tripStatus]), it) },
        )
    }
}

/** 12.3f as 12.3, not 12.300000190734863: the shortest decimal that reads back as the same float. */
private fun Float.decimal(): Double = toString().toDouble()
