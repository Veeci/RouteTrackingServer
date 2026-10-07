package veeci.practicing.rts

import veeci.practicing.rts.protocol.FixDto
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.simulator.GpxReader
import veeci.practicing.rts.simulator.toFixes
import veeci.practicing.rts.testing.TestDatabase
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import kotlin.time.Instant

const val DRIVER_PATH = "/ws/v1/driver"

/**
 * What the server stored for one session, read straight from Postgres. A test checks the database this way, not
 * only the acks: an ack can be lost while its batch is stored.
 */
class StoredTrack(
    private val sessionId: String,
    private val database: Map<String, String> = TestDatabase.env(),
) {
    /** The seq of every stored batch, in order. */
    fun seqs(): List<Long> = query("SELECT seq FROM fix_batches WHERE session_id = ? ORDER BY seq") { it.getLong(1) }

    fun fixCount(): Long = query("SELECT count(*) FROM fixes WHERE session_id = ?") { it.getLong(1) }.single()

    /** The running total of the newest fix: the distance the server says the vehicle travelled. */
    fun lastCumulativeM(): Double =
        query("SELECT cumulative_m FROM fixes WHERE session_id = ? ORDER BY recorded_at DESC LIMIT 1") { it.getDouble(1) }.single()

    private fun <T> query(
        sql: String,
        read: (ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection(database["DB_URL"], database["DB_USER"], database["DB_PASSWORD"]).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, UUID.fromString(sessionId))
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
            }
        }
}

/** A route from the simulator's resources, moved in time so that it ends now, as the simulator replays it. */
fun routeFixes(resource: String): List<FixDto> = GpxReader.load(resource).toFixes(now())

fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())

/** The route length as the server computes it: the sum of the distances between neighbouring fixes. */
fun List<FixDto>.lengthM(): Double = zipWithNext { a, b -> Haversine.distance(GeoPoint(a.lat, a.lng), GeoPoint(b.lat, b.lng)).value }.sum()
