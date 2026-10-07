package veeci.practicing.rts

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.protocol.Ack
import veeci.practicing.rts.simulator.DriverClient
import veeci.practicing.rts.simulator.GpxReader
import veeci.practicing.rts.simulator.toFixes
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.testApp
import java.sql.DriverManager
import kotlin.time.Instant

/** The simulator's driver client replays recorded routes against the real application and Postgres. */
@Testcontainers(disabledWithoutDocker = true)
class DriverReplayE2ETest {
    private val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

    @Test
    fun `TC-2-E2E-01 a 2 km route is stored completely, and its running total is 2 km`() =
        testApp {
            val acks = mutableListOf<Ack>()
            val client = DriverClient(createClient { install(WebSockets) }, "/ws/v1/driver", "e2e-straight", onAck = { acks += it })
            GpxReader
                .load("routes/straight_2km.gpx")
                .toFixes(now)
                .chunked(10)
                .forEach(client::enqueue)

            client.connectAndSend()

            client.pending shouldBe 0
            acks.map { it.seq } shouldBe (1L..17L).toList()
            storedFixes(client.sessionId) shouldBe acks.sumOf { it.accepted }.toLong()
            lastCumulativeM(client.sessionId) shouldBe (2_000.0 plusOrMinus 60.0) // ±3 %
        }

    @Test
    fun `reconnecting every 5 batches stores each batch exactly once`() =
        testApp {
            val client = DriverClient(createClient { install(WebSockets) }, "/ws/v1/driver", "e2e-reconnect")
            GpxReader
                .load("routes/city_loop.gpx")
                .toFixes(now)
                .chunked(10)
                .forEach(client::enqueue)

            var connections = 0
            while (client.pending > 0) {
                connections++
                client.connectAndSend(maxBatches = 5)
            }

            connections shouldBe 4 // 17 batches, 5 per connection
            storedBatches(client.sessionId) shouldBe 17
        }

    private fun storedFixes(session: String) = count("SELECT count(*) FROM fixes WHERE session_id = '$session'")

    private fun storedBatches(session: String) = count("SELECT count(*) FROM fix_batches WHERE session_id = '$session'")

    private fun lastCumulativeM(session: String): Double =
        query("SELECT cumulative_m FROM fixes WHERE session_id = '$session' ORDER BY recorded_at DESC LIMIT 1") { it.getDouble(1) }

    private fun count(sql: String): Long = query(sql) { it.getLong(1) }

    private fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): T {
        val env = TestDatabase.env()
        return DriverManager.getConnection(env["DB_URL"], env["DB_USER"], env["DB_PASSWORD"]).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    read(rows)
                }
            }
        }
    }
}
