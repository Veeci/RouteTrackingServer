package veeci.practicing.rts.trip.adapter.out.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripRoute

/** The `trip_routes` table (migration V11). */
internal object TripRoutesTable : Table("trip_routes") {
    val tripId = javaUUID("trip_id")
    val kind = text("kind")
    val version = integer("version")
    val polyline = text("polyline")
    val distanceM = double("distance_m")
    val durationS = double("duration_s")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(tripId, kind, version)
}

/** Runs inside the caller's transaction (see TransactionRunner); it opens none of its own. */
class ExposedTripRouteRepository : TripRouteRepository {
    override suspend fun insert(route: TripRoute) {
        TripRoutesTable.insert {
            it[tripId] = route.tripId.value
            it[kind] = route.kind.name
            it[version] = route.version
            it[polyline] = route.polyline
            it[distanceM] = route.distanceM
            it[durationS] = route.durationS
            it[createdAt] = route.createdAt
        }
    }

    // The primary key (trip_id, kind, version) serves this query: an index scan backwards, one row.
    override suspend fun findLatest(
        tripId: TripId,
        kind: RouteKind,
    ): TripRoute? =
        TripRoutesTable
            .selectAll()
            .where { (TripRoutesTable.tripId eq tripId.value) and (TripRoutesTable.kind eq kind.name) }
            .orderBy(TripRoutesTable.version to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toRoute()

    private fun ResultRow.toRoute() =
        TripRoute(
            tripId = TripId(this[TripRoutesTable.tripId]),
            kind = RouteKind.valueOf(this[TripRoutesTable.kind]),
            version = this[TripRoutesTable.version],
            polyline = this[TripRoutesTable.polyline],
            distanceM = this[TripRoutesTable.distanceM],
            durationS = this[TripRoutesTable.durationS],
            createdAt = this[TripRoutesTable.createdAt],
        )
}
