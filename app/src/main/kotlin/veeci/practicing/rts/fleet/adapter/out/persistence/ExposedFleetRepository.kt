package veeci.practicing.rts.fleet.adapter.out.persistence

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.avg
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.doubleLiteral
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
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
import veeci.practicing.rts.fleet.domain.MapCell
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

    // Two indexes answer it together (a BitmapOr): fleet_drivers_updated and fleet_drivers_last_seen.
    override suspend fun changedSince(
        since: Instant,
        staleFrom: Instant,
        staleTo: Instant,
    ): List<FleetDriver> =
        table
            .selectAll()
            .where { (table.updatedAt greater since) or table.lastSeenAt.between(staleFrom, staleTo) }
            .map { it.toDriver() }

    // The cell size is a literal, not a bind parameter: Postgres matches a GROUP BY expression with the SELECT one by
    // its text, and two parameters ($1, $2) never match even when they hold the same value.
    override suspend fun cells(
        filter: FleetFilter,
        onlineSince: Instant,
        cellDeg: Double,
    ): List<MapCell> {
        val latCell = CustomFunction<Double?>("FLOOR", DoubleColumnType(), table.lat / doubleLiteral(cellDeg))
        val lngCell = CustomFunction<Double?>("FLOOR", DoubleColumnType(), table.lng / doubleLiteral(cellDeg))
        val lat = table.lat.avg(MEAN_SCALE)
        val lng = table.lng.avg(MEAN_SCALE)
        val drivers = table.driverId.count()
        return table
            .select(latCell, lngCell, lat, lng, drivers)
            .where { FleetConditions.matching(filter, onlineSince) }
            .groupBy(latCell, lngCell)
            .map { MapCell(checkNotNull(it[lat]).toDouble(), checkNotNull(it[lng]).toDouble(), it[drivers].toInt()) }
    }

    override suspend fun clear() {
        TransactionManager.current().exec("TRUNCATE fleet_drivers")
    }

    override suspend fun insertAll(
        drivers: Collection<FleetDriver>,
        updatedAt: Instant,
    ) {
        table.batchInsert(drivers, shouldReturnGeneratedValues = false) { fill(it, updatedAt) }
    }
}

private fun count(condition: Op<Boolean>): Int =
    FleetDriversTable
        .selectAll()
        .where { condition }
        .count()
        .toInt()

private fun UpdateBuilder<*>.fill(
    driver: FleetDriver,
    updatedAt: Instant,
) {
    this[FleetDriversTable.driverId] = driver.driverId
    this[FleetDriversTable.displayName] = driver.displayName
    this[FleetDriversTable.enabled] = driver.enabled
    this[FleetDriversTable.lastSeenAt] = driver.lastSeenAt
    this[FleetDriversTable.lat] = driver.position?.lat
    this[FleetDriversTable.lng] = driver.position?.lng
    this[FleetDriversTable.speedMps] = driver.position?.speedMps?.toFloat()
    this[FleetDriversTable.bearingDeg] = driver.position?.bearingDeg?.toFloat()
    this[FleetDriversTable.positionAt] = driver.position?.at
    this[FleetDriversTable.batteryPct] = driver.device?.batteryPct?.toShort()
    this[FleetDriversTable.isCharging] = driver.device?.isCharging
    this[FleetDriversTable.motion] = driver.device?.motion
    this[FleetDriversTable.statusAt] = driver.device?.at
    this[FleetDriversTable.currentTripId] = driver.trip?.currentTripId
    this[FleetDriversTable.tripStatus] = driver.trip?.status
    this[FleetDriversTable.tripStatusAt] = driver.trip?.at
    this[FleetDriversTable.updatedAt] = updatedAt
}

private fun ResultRow.toDriver(): FleetDriver {
    val positionAt = this[FleetDriversTable.positionAt]
    val statusAt = this[FleetDriversTable.statusAt]
    val tripAt = this[FleetDriversTable.tripStatusAt]
    return FleetDriver(
        driverId = this[FleetDriversTable.driverId],
        displayName = this[FleetDriversTable.displayName],
        enabled = this[FleetDriversTable.enabled],
        position =
            positionAt?.let {
                DriverPosition(
                    checkNotNull(this[FleetDriversTable.lat]),
                    checkNotNull(this[FleetDriversTable.lng]),
                    this[FleetDriversTable.speedMps]?.decimal(),
                    this[FleetDriversTable.bearingDeg]?.decimal(),
                    it,
                )
            },
        device =
            statusAt?.let {
                DeviceState(
                    checkNotNull(this[FleetDriversTable.batteryPct]).toInt(),
                    checkNotNull(this[FleetDriversTable.isCharging]),
                    this[FleetDriversTable.motion],
                    it,
                )
            },
        trip = tripAt?.let { TripState(this[FleetDriversTable.currentTripId], checkNotNull(this[FleetDriversTable.tripStatus]), it) },
    )
}

/** Decimals kept of a cell's mean position: 6 is about 0.1 m. */
private const val MEAN_SCALE = 6

/** 12.3f as 12.3, not 12.300000190734863: the shortest decimal that reads back as the same float. */
private fun Float.decimal(): Double = toString().toDouble()
