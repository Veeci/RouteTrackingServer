package veeci.practicing.rts.trip.adapter.out.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.RouteReason
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripRoute

/** The `trip_routes` table (migrations V11 and V12). */
internal object TripRoutesTable : Table("trip_routes") {
    val tripId = javaUUID("trip_id")
    val kind = text("kind")
    val version = integer("version")
    val polyline = text("polyline")
    val distanceM = double("distance_m")
    val durationS = double("duration_s")
    val createdAt = timestamp("created_at")
    val reason = text("reason")
    override val primaryKey = PrimaryKey(tripId, kind, version)
}

/** Runs inside the caller's transaction (see TransactionRunner); it opens none of its own. */
class ExposedTripRouteRepository : TripRouteRepository {
    override suspend fun insert(route: TripRoute) {
        TripRoutesTable.insert { fill(it, route) }
    }

    // ON CONFLICT DO NOTHING: a plain insert that hits the primary key aborts the whole Postgres transaction.
    override suspend fun insertIfAbsent(route: TripRoute): Boolean = TripRoutesTable.insertIgnore { fill(it, route) }.insertedCount == 1

    private fun TripRoutesTable.fill(
        row: UpdateBuilder<*>,
        route: TripRoute,
    ) {
        row[tripId] = route.tripId.value
        row[kind] = route.kind.name
        row[version] = route.version
        row[polyline] = route.polyline
        row[distanceM] = route.distanceM
        row[durationS] = route.durationS
        row[createdAt] = route.createdAt
        row[reason] = route.reason.name
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
            reason = RouteReason.valueOf(this[TripRoutesTable.reason]),
        )
}
