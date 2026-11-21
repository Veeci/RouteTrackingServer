package veeci.practicing.rts.fleet.adapter.out.persistence

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
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
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.batchUpsert
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

    override suspend fun update(
        driverId: UUID,
        updatedAt: Instant,
        change: (FleetDriver) -> FleetDriver?,
    ) {
        val current =
            table
                .selectAll()
                .where { table.driverId eq driverId }
                .forUpdate()
                .singleOrNull()
                ?.toDriver() ?: FleetDriver(driverId)
        change(current)?.let { changed -> table.upsert { it.fill(changed, updatedAt) } }
    }

    // INSERT ... ON CONFLICT (driver_id) DO UPDATE ... WHERE the stored time is older. Postgres locks the row before it
    // checks the WHERE, so two writers of one driver still take turns, as they do in update(). When the WHERE
    // is false it writes nothing. In SET, a column of fleet_drivers is the stored value, before this statement.
    override suspend fun savePositions(
        positions: Map<UUID, DriverPosition>,
        updatedAt: Instant,
    ) {
        if (positions.isEmpty()) return
        table.batchUpsert(
            positions.entries,
            table.driverId,
            onUpdate = {
                it[table.lat] = insertValue(table.lat)
                it[table.lng] = insertValue(table.lng)
                it[table.speedMps] = insertValue(table.speedMps)
                it[table.bearingDeg] = insertValue(table.bearingDeg)
                it[table.positionAt] = insertValue(table.positionAt)
                it[table.lastSeenAt] = newerOf(insertValue(table.positionAt), table.statusAt)
                it[table.updatedAt] = insertValue(table.updatedAt)
            },
            where = { newerThanStored(table.positionAt) },
            shouldReturnGeneratedValues = false,
        ) { (driverId, position) ->
            this[FleetDriversTable.driverId] = driverId
            this[FleetDriversTable.enabled] = true
            this[FleetDriversTable.lat] = position.lat
            this[FleetDriversTable.lng] = position.lng
            this[FleetDriversTable.speedMps] = position.speedMps?.toFloat()
            this[FleetDriversTable.bearingDeg] = position.bearingDeg?.toFloat()
            this[FleetDriversTable.positionAt] = position.at
            this[FleetDriversTable.lastSeenAt] = position.at
            this[FleetDriversTable.updatedAt] = updatedAt
        }
    }

    override suspend fun saveDevices(
        devices: Map<UUID, DeviceState>,
        updatedAt: Instant,
    ) {
        if (devices.isEmpty()) return
        table.batchUpsert(
            devices.entries,
            table.driverId,
            onUpdate = {
                it[table.batteryPct] = insertValue(table.batteryPct)
                it[table.isCharging] = insertValue(table.isCharging)
                it[table.motion] = insertValue(table.motion)
                it[table.statusAt] = insertValue(table.statusAt)
                it[table.lastSeenAt] = newerOf(table.positionAt, insertValue(table.statusAt))
                it[table.updatedAt] = insertValue(table.updatedAt)
            },
            where = { newerThanStored(table.statusAt) },
            shouldReturnGeneratedValues = false,
        ) { (driverId, device) ->
            this[FleetDriversTable.driverId] = driverId
            this[FleetDriversTable.enabled] = true
            this[FleetDriversTable.batteryPct] = device.batteryPct.toShort()
            this[FleetDriversTable.isCharging] = device.isCharging
            this[FleetDriversTable.motion] = device.motion
            this[FleetDriversTable.statusAt] = device.at
            this[FleetDriversTable.lastSeenAt] = device.at
            this[FleetDriversTable.updatedAt] = updatedAt
        }
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

/** `EXCLUDED.column`: the value the upsert tried to insert. Exposed's `insertValue` gives it only inside `onUpdate`. */
private class Excluded<T>(
    private val column: Column<T>,
) : Expression<T>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("EXCLUDED.").append(TransactionManager.current().identity(column))
    }
}

/** True when the row has no [stored] time yet, or the upsert brings a later one: the newer-wins rule of the fleet. */
private fun newerThanStored(stored: Column<Instant?>): Op<Boolean> = stored.isNull() or (stored less Excluded(stored))

/** GREATEST ignores nulls: the newer of two times, either of which may be missing. */
private fun newerOf(
    a: Expression<Instant?>,
    b: Expression<Instant?>,
): Expression<Instant?> = CustomFunction("GREATEST", FleetDriversTable.lastSeenAt.columnType, a, b)

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
