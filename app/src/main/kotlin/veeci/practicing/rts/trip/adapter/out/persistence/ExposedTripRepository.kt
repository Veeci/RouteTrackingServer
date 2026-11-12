package veeci.practicing.rts.trip.adapter.out.persistence

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.application.port.out.TripCursor
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripStatus

/** The `trips` table (migration V6). */
internal object TripsTable : Table("trips") {
    val id = javaUUID("id")
    val guestId = javaUUID("guest_id")
    val driverId = javaUUID("driver_id").nullable()
    val pickupLat = double("pickup_lat")
    val pickupLng = double("pickup_lng")
    val destinationLat = double("destination_lat")
    val destinationLng = double("destination_lng")
    val status = text("status")
    val version = long("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Runs inside the caller's transaction (see TransactionRunner); it opens none of its own. */
class ExposedTripRepository : TripRepository {
    override suspend fun insert(trip: Trip) {
        TripsTable.insert {
            it[id] = trip.id.value
            it[guestId] = trip.guestId.value
            it[driverId] = trip.driverId?.value
            it[pickupLat] = trip.pickup.lat
            it[pickupLng] = trip.pickup.lng
            it[destinationLat] = trip.destination.lat
            it[destinationLng] = trip.destination.lng
            it[status] = trip.status.name
            it[version] = trip.version
            it[createdAt] = trip.createdAt
            it[updatedAt] = trip.updatedAt
        }
    }

    override suspend fun find(id: TripId): Trip? =
        TripsTable
            .selectAll()
            .where { TripsTable.id eq id.value }
            .singleOrNull()
            ?.toTrip()

    override suspend fun findByStatus(
        status: TripStatus,
        after: TripCursor?,
        limit: Int,
    ): List<Trip> =
        TripsTable
            .selectAll()
            .where { (TripsTable.status eq status.name) and after.startsAfter() }
            .orderBy(TripsTable.createdAt to SortOrder.ASC, TripsTable.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toTrip() }

    override suspend fun findActive(): List<Trip> =
        TripsTable
            .selectAll()
            .where { TripsTable.status inList ACTIVE }
            .map { it.toTrip() }

    override suspend fun findActiveForDriver(driver: UserId): Trip? =
        TripsTable
            .selectAll()
            .where { (TripsTable.driverId eq driver.value) and (TripsTable.status inList ACTIVE) }
            .singleOrNull()
            ?.toTrip()

    override suspend fun update(trip: Trip): Boolean =
        try {
            // Only the row with the version that was read changes; 0 rows means someone saved first.
            TripsTable.update({ (TripsTable.id eq trip.id.value) and (TripsTable.version eq trip.version) }) {
                it[driverId] = trip.driverId?.value
                it[status] = trip.status.name
                it[updatedAt] = trip.updatedAt
                it[version] = trip.version + 1
            } == 1
        } catch (e: ExposedSQLException) {
            if (e.sqlState == UNIQUE_VIOLATION && e.message.orEmpty().contains(ONE_ACTIVE_TRIP_INDEX)) {
                throw DomainException(TripError.DRIVER_ALREADY_ON_TRIP, "The driver already has an active trip")
            }
            throw e
        }

    /** Keyset paging: rows after the cursor in (created_at, id) order. */
    private fun TripCursor?.startsAfter(): Op<Boolean> =
        if (this == null) {
            Op.TRUE
        } else {
            (TripsTable.createdAt greater createdAt) or ((TripsTable.createdAt eq createdAt) and (TripsTable.id greater id.value))
        }

    private fun ResultRow.toTrip() =
        Trip.restore(
            id = TripId(this[TripsTable.id]),
            guestId = UserId(this[TripsTable.guestId]),
            driverId = this[TripsTable.driverId]?.let(::UserId),
            pickup = GeoPoint(this[TripsTable.pickupLat], this[TripsTable.pickupLng]),
            destination = GeoPoint(this[TripsTable.destinationLat], this[TripsTable.destinationLng]),
            status = TripStatus.valueOf(this[TripsTable.status]),
            createdAt = this[TripsTable.createdAt],
            updatedAt = this[TripsTable.updatedAt],
            version = this[TripsTable.version],
        )

    private companion object {
        val ACTIVE = TripStatus.entries.filter { it.isActive }.map { it.name }
        const val UNIQUE_VIOLATION = "23505"
        const val ONE_ACTIVE_TRIP_INDEX = "one_active_trip_per_driver"
    }
}
