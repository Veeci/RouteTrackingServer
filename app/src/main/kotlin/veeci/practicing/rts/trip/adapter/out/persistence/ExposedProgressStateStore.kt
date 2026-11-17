package veeci.practicing.rts.trip.adapter.out.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.json.jsonb
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.MeasuredPolyline
import veeci.practicing.rts.shared.geo.PolylineCodec
import veeci.practicing.rts.trip.application.port.out.FollowedRoute
import veeci.practicing.rts.trip.application.port.out.ProgressState
import veeci.practicing.rts.trip.application.port.out.ProgressStateStore
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.progress.MatchState
import java.util.Collections
import java.util.UUID
import kotlin.time.Instant

/**
 * Progress state in `trip_progress_state` (V17), in the caller's transaction. The followed route is stored as its
 * kind and version only. Its decoded line comes from a cache of [CACHED_LINES] lines, filled from [routes] on a
 * miss. A route version never changes once stored, so a cached line is never out of date on any instance.
 */
class ExposedProgressStateStore(
    private val routes: TripRouteRepository,
) : ProgressStateStore {
    private val lines =
        Collections.synchronizedMap(
            object : LinkedHashMap<LineKey, MeasuredPolyline>(CACHED_LINES, LOAD_FACTOR, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LineKey, MeasuredPolyline>) = size > CACHED_LINES
            },
        )

    override suspend fun get(trip: TripId): ProgressState? {
        val stored =
            ProgressStateTable
                .selectAll()
                .where { ProgressStateTable.tripId eq trip.value }
                .singleOrNull()
                ?.get(ProgressStateTable.state) ?: return null
        val route = stored.route?.let { route -> line(trip, route.kind, route.version)?.let { route.toFollowed(it) } }
        return ProgressState(stored.speedMps, GeoPoint(stored.lat, stored.lng), stored.lastAt, route, stored.lastRerouteAt)
    }

    override suspend fun put(
        trip: TripId,
        state: ProgressState,
    ) {
        state.route?.let { lines[LineKey(trip.value, it.kind, it.version)] = it.line }
        ProgressStateTable.upsert {
            it[tripId] = trip.value
            it[this.state] = StoredProgress.of(state)
            it[updatedAt] = CurrentTimestamp
        }
    }

    override suspend fun remove(trip: TripId) {
        ProgressStateTable.deleteWhere { tripId eq trip.value }
        synchronized(lines) { lines.keys.removeIf { it.trip == trip.value } }
    }

    /** Null when the route is gone, which a stored state never points at; progress then starts the route again. */
    private suspend fun line(
        trip: TripId,
        kind: RouteKind,
        version: Int,
    ): MeasuredPolyline? {
        val key = LineKey(trip.value, kind, version)
        return lines[key]
            ?: routes
                .findAll(trip)
                .firstOrNull { it.kind == kind && it.version == version }
                ?.let { MeasuredPolyline(PolylineCodec.decode(it.polyline)) }
                ?.also { lines[key] = it }
    }

    private data class LineKey(
        val trip: UUID,
        val kind: RouteKind,
        val version: Int,
    )

    private companion object {
        const val CACHED_LINES = 1_000
        const val LOAD_FACTOR = 0.75f
    }
}

/** The `trip_progress_state` table (migration V17). */
internal object ProgressStateTable : Table("trip_progress_state") {
    val tripId = javaUUID("trip_id")
    val state = jsonb("state", Json, StoredProgress.serializer())
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(tripId)
}

/** `trip_progress_state.state`. Derived data: a shape change may simply drop old rows, which progress rebuilds. */
@Serializable
internal data class StoredProgress(
    val speedMps: Double? = null,
    val lat: Double,
    val lng: Double,
    val lastAt: Instant,
    val lastRerouteAt: Instant? = null,
    val route: StoredRoute? = null,
) {
    companion object {
        fun of(state: ProgressState) =
            StoredProgress(
                state.speedMps,
                state.lastPoint.lat,
                state.lastPoint.lng,
                state.lastAt,
                state.lastRerouteAt,
                state.route?.let {
                    StoredRoute(
                        it.kind,
                        it.version,
                        it.distanceM,
                        it.durationS,
                        it.match?.cursor,
                        it.match?.alongM,
                        it.offRouteCount,
                    )
                },
            )
    }
}

@Serializable
internal data class StoredRoute(
    val kind: RouteKind,
    val version: Int,
    val distanceM: Double,
    val durationS: Double,
    val cursor: Int? = null,
    val alongM: Double? = null,
    val offRouteCount: Int = 0,
) {
    fun toFollowed(line: MeasuredPolyline) =
        FollowedRoute(
            kind,
            version,
            line,
            distanceM,
            durationS,
            match = if (cursor != null && alongM != null) MatchState(cursor, alongM) else null,
            offRouteCount = offRouteCount,
        )
}
