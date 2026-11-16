package veeci.practicing.rts.trip.adapter.out.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.json.jsonb
import veeci.practicing.rts.trip.application.port.out.TripSummaryRepository
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.summary.LegSummary
import veeci.practicing.rts.trip.domain.summary.TripSummary
import kotlin.time.Instant

/** The `trip_summaries` table (migration V13). */
internal object TripSummariesTable : Table("trip_summaries") {
    val tripId = javaUUID("trip_id")
    val summary = jsonb("summary", Json, TripSummaryV1.serializer())
    val computedAt = timestamp("computed_at")
    override val primaryKey = PrimaryKey(tripId)
}

/**
 * A summary as stored in `trip_summaries.summary`. A contract with older rows: fields may be added with a default,
 * but a change to an existing field needs a new version, and old rows are then computed again.
 */
@Serializable
internal data class TripSummaryV1(
    val ride: LegV1,
    val approach: LegV1? = null,
    val plannedDistanceM: Double? = null,
    val offRouteCount: Int,
    val rerouteCount: Int,
    val rejected: Map<String, Int>,
    val approximate: Boolean,
) {
    @Serializable
    data class LegV1(
        val distanceM: Double,
        val durationS: Long,
        val movingTimeS: Long,
        val avgSpeedMps: Double,
        val maxSpeedMps: Double,
        val pointCount: Int,
        val startedAt: Instant,
        val endedAt: Instant,
    ) {
        fun toLeg() = LegSummary(distanceM, durationS, movingTimeS, avgSpeedMps, maxSpeedMps, pointCount, startedAt, endedAt)
    }

    fun toSummary(
        tripId: TripId,
        computedAt: Instant,
    ) = TripSummary(
        tripId,
        ride.toLeg(),
        approach?.toLeg(),
        plannedDistanceM,
        offRouteCount,
        rerouteCount,
        rejected,
        approximate,
        computedAt,
    )

    companion object {
        fun of(summary: TripSummary) =
            TripSummaryV1(
                summary.ride.toV1(),
                summary.approach?.toV1(),
                summary.plannedDistanceM,
                summary.offRouteCount,
                summary.rerouteCount,
                summary.rejected,
                summary.approximate,
            )

        private fun LegSummary.toV1() = LegV1(distanceM, durationS, movingTimeS, avgSpeedMps, maxSpeedMps, pointCount, startedAt, endedAt)
    }
}

/** Runs inside the caller's transaction; it opens none of its own. */
class ExposedTripSummaryRepository : TripSummaryRepository {
    override suspend fun save(summary: TripSummary) {
        TripSummariesTable.upsert {
            it[tripId] = summary.tripId.value
            it[TripSummariesTable.summary] = TripSummaryV1.of(summary)
            it[computedAt] = summary.computedAt
        }
    }

    override suspend fun find(tripId: TripId): TripSummary? =
        TripSummariesTable
            .selectAll()
            .where { TripSummariesTable.tripId eq tripId.value }
            .singleOrNull()
            ?.toSummary()

    override suspend fun findAll(tripIds: Collection<TripId>): Map<TripId, TripSummary> =
        if (tripIds.isEmpty()) {
            emptyMap() // `IN ()` is not valid SQL
        } else {
            TripSummariesTable
                .selectAll()
                .where { TripSummariesTable.tripId inList tripIds.map { it.value } }
                .map { it.toSummary() }
                .associateBy { it.tripId }
        }

    private fun ResultRow.toSummary() =
        this[TripSummariesTable.summary].toSummary(TripId(this[TripSummariesTable.tripId]), this[TripSummariesTable.computedAt])
}
