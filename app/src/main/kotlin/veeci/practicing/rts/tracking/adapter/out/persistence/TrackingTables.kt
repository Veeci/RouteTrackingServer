package veeci.practicing.rts.tracking.adapter.out.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

// Exposed descriptions of the tables in migration V3. They must match the SQL; ExposedTrackingIT checks
// every column by writing and reading it back.

internal object TrackingSessionsTable : Table("tracking_sessions") {
    val id = javaUUID("id")
    val deviceId = text("device_id")
    val sdkVersion = text("sdk_version")
    val startedAt = timestamp("started_at")
    val lastSeenAt = timestamp("last_seen_at")
    override val primaryKey = PrimaryKey(id)
}

/** A rejection as stored in `fix_batches.rejections`. */
@Serializable
internal data class RejectionRow(
    val index: Int,
    val reason: String,
)

internal object FixBatchesTable : Table("fix_batches") {
    val sessionId = javaUUID("session_id")
    val seq = long("seq")
    val receivedAt = timestamp("received_at")
    val acceptedCount = integer("accepted_count")
    val rejections = jsonb("rejections", Json, ListSerializer(RejectionRow.serializer()))
    override val primaryKey = PrimaryKey(sessionId, seq)
}

internal object FixesTable : Table("fixes") {
    val sessionId = javaUUID("session_id")
    val seq = long("seq")
    val idx = integer("idx")
    val lat = double("lat")
    val lng = double("lng")
    val accuracyM = float("accuracy_m")
    val speedMps = float("speed_mps").nullable()
    val speedAccuracyMps = float("speed_accuracy_mps").nullable()
    val bearingDeg = float("bearing_deg").nullable()
    val bearingAccuracyDeg = float("bearing_accuracy_deg").nullable()
    val altitudeM = float("altitude_m").nullable()
    val verticalAccuracyM = float("vertical_accuracy_m").nullable()
    val recordedAt = timestamp("recorded_at")
    val provider = text("provider")
    val mock = bool("mock")
    val satUsed = short("sat_used").nullable()
    val satMeanCn0DbHz = float("sat_mean_cn0_dbhz").nullable()
    val distanceFromPrevM = double("distance_from_prev_m")
    val cumulativeM = double("cumulative_m")
    val derivedSpeedMps = float("derived_speed_mps").nullable()
    override val primaryKey = PrimaryKey(sessionId, seq, idx)
}
