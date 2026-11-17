package veeci.practicing.rts.telemetry.adapter.out.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.json.jsonb
import veeci.practicing.rts.telemetry.application.port.out.SessionHealthRepository
import veeci.practicing.rts.telemetry.domain.DeviceKey
import veeci.practicing.rts.telemetry.domain.Gap
import veeci.practicing.rts.telemetry.domain.SessionHealth
import veeci.practicing.rts.telemetry.domain.SessionHealthRecord
import veeci.practicing.rts.tracking.application.api.SessionMark
import java.util.UUID
import kotlin.time.Instant

/** Runs inside the caller's transaction; it opens none of its own. */
class ExposedSessionHealthRepository : SessionHealthRepository {
    override suspend fun save(record: SessionHealthRecord) {
        SessionHealthTable.upsert {
            it[sessionId] = record.sessionId
            it[driverId] = record.driverId
            it[startedAt] = record.startedAt
            it[sessionLastSeenAt] = record.sessionLastSeenAt
            it[computedAt] = record.computedAt
            it[primaryProvider] = record.health.primaryProvider
            it[manufacturer] = record.device?.manufacturer
            it[model] = record.device?.model
            it[androidSdk] = record.device?.androidSdk
            it[rom] = record.device?.rom
            it[hasGms] = record.device?.hasGms
            it[metrics] = StoredMetrics.of(record.health)
        }
    }

    override suspend fun find(sessionId: UUID): SessionHealthRecord? =
        SessionHealthTable
            .selectAll()
            .where { SessionHealthTable.sessionId eq sessionId }
            .singleOrNull()
            ?.toRecord()

    override suspend fun startedBetween(
        from: Instant,
        to: Instant,
    ): List<SessionHealthRecord> =
        SessionHealthTable
            .selectAll()
            .where { (SessionHealthTable.startedAt greaterEq from) and (SessionHealthTable.startedAt less to) }
            .map { it.toRecord() }

    override suspend fun watermark(): SessionMark? =
        WatermarkTable
            .selectAll()
            .singleOrNull()
            ?.let { SessionMark(it[WatermarkTable.lastSeenAt], it[WatermarkTable.sessionId]) }

    override suspend fun moveWatermark(to: SessionMark) {
        WatermarkTable.upsert {
            it[id] = ONLY_ROW
            it[lastSeenAt] = to.lastSeenAt
            it[sessionId] = to.sessionId
        }
    }

    private fun ResultRow.toRecord(): SessionHealthRecord {
        val device =
            DeviceKey(
                this[SessionHealthTable.manufacturer],
                this[SessionHealthTable.model],
                this[SessionHealthTable.androidSdk],
                this[SessionHealthTable.rom],
                this[SessionHealthTable.hasGms],
            )
        return SessionHealthRecord(
            sessionId = this[SessionHealthTable.sessionId],
            driverId = this[SessionHealthTable.driverId],
            startedAt = this[SessionHealthTable.startedAt],
            sessionLastSeenAt = this[SessionHealthTable.sessionLastSeenAt],
            device = device.takeUnless { it == DeviceKey(null, null, null, null, null) },
            computedAt = this[SessionHealthTable.computedAt],
            health = this[SessionHealthTable.metrics].toHealth(this[SessionHealthTable.primaryProvider]),
        )
    }

    private companion object {
        const val ONLY_ROW: Short = 1
    }
}

/** The `session_health` table (migration V16). */
internal object SessionHealthTable : Table("session_health") {
    val sessionId = javaUUID("session_id")
    val driverId = javaUUID("driver_id").nullable()
    val startedAt = timestamp("started_at")
    val sessionLastSeenAt = timestamp("session_last_seen_at")
    val computedAt = timestamp("computed_at")
    val primaryProvider = text("primary_provider").nullable()
    val manufacturer = text("manufacturer").nullable()
    val model = text("model").nullable()
    val androidSdk = integer("android_sdk").nullable()
    val rom = text("rom").nullable()
    val hasGms = bool("has_gms").nullable()
    val metrics = jsonb("metrics", Json, StoredMetrics.serializer())
    override val primaryKey = PrimaryKey(sessionId)
}

/** The `session_health_watermark` table (migration V16). */
internal object WatermarkTable : Table("session_health_watermark") {
    val id = short("id")
    val lastSeenAt = timestamp("last_seen_at")
    val sessionId = javaUUID("session_id")
    override val primaryKey = PrimaryKey(id)
}

/**
 * `session_health.metrics`. A contract with stored rows: fields may be added with defaults, and a change to an
 * existing field needs a new shape. `primaryProvider` has its own column, so it is not repeated here.
 */
@Serializable
internal data class StoredMetrics(
    val fixCount: Int,
    val rejectedCount: Int,
    val acceptedRatio: Double? = null,
    val rejectionsByReason: Map<String, Int> = emptyMap(),
    val intervalP50S: Double? = null,
    val intervalP95S: Double? = null,
    val accuracyP50M: Double? = null,
    val accuracyP95M: Double? = null,
    val gaps: List<StoredGap> = emptyList(),
    val batteryDrainPctPerHour: Double? = null,
    val providerMix: Map<String, Double> = emptyMap(),
) {
    fun toHealth(primaryProvider: String?) =
        SessionHealth(
            fixCount,
            rejectedCount,
            acceptedRatio,
            rejectionsByReason,
            intervalP50S,
            intervalP95S,
            accuracyP50M,
            accuracyP95M,
            gaps.map { Gap(it.start, it.durationS, it.expectedIntervalS, it.likelyDoze) },
            batteryDrainPctPerHour,
            providerMix,
            primaryProvider,
        )

    companion object {
        fun of(health: SessionHealth) =
            StoredMetrics(
                health.fixCount,
                health.rejectedCount,
                health.acceptedRatio,
                health.rejectionsByReason,
                health.intervalP50S,
                health.intervalP95S,
                health.accuracyP50M,
                health.accuracyP95M,
                health.gaps.map { StoredGap(it.start, it.durationS, it.expectedIntervalS, it.likelyDoze) },
                health.batteryDrainPctPerHour,
                health.providerMix,
            )
    }
}

@Serializable
internal data class StoredGap(
    val start: Instant,
    val durationS: Double,
    val expectedIntervalS: Double,
    val likelyDoze: Boolean,
)
