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
import veeci.practicing.rts.testing.testApp
import kotlin.time.Duration.Companion.seconds

/** The simulator's driver client replays recorded routes against the real application and Postgres. */
@Testcontainers(disabledWithoutDocker = true)
class DriverReplayE2ETest {
    @Test
    fun `TC-2-E2E-01 a 2 km route is stored completely, and its running total is 2 km`() =
        testApp {
            val acks = mutableListOf<Ack>()
            val client = DriverClient(createClient { install(WebSockets) }, DRIVER_PATH, "e2e-straight", onAck = { acks += it })
            routeFixes("routes/straight_2km.gpx").chunked(10).forEach(client::enqueue)

            client.connectAndSend()

            val stored = StoredTrack(client.sessionId)
            client.pending shouldBe 0
            acks.map { it.seq } shouldBe (1L..17L).toList()
            stored.fixCount() shouldBe acks.sumOf { it.accepted }.toLong()
            stored.lastCumulativeM() shouldBe (2_000.0 plusOrMinus 60.0) // ±3 %
        }

    @Test
    fun `reconnecting every 5 batches stores each batch exactly once`() =
        testApp {
            val client = DriverClient(createClient { install(WebSockets) }, DRIVER_PATH, "e2e-reconnect")
            routeFixes("routes/city_loop.gpx").chunked(10).forEach(client::enqueue)

            client.sendAll(batchesPerConnection = 5) shouldBe true

            client.connections shouldBe 4 // 17 batches, 5 per connection
            StoredTrack(client.sessionId).seqs() shouldBe (1L..17L).toList()
        }

    @Test
    fun `TC-2-E2E-03 a 90 s gap in a tunnel is accepted, because the speed across it is plausible`() =
        testApp {
            val points = GpxReader.load("routes/tunnel_gap.gpx")
            points.zipWithNext { a, b -> b.time - a.time }.max() shouldBe 90.seconds // the fixture really has the gap
            val fixes = points.toFixes(now())
            val acks = mutableListOf<Ack>()
            val client = DriverClient(createClient { install(WebSockets) }, DRIVER_PATH, "e2e-tunnel", onAck = { acks += it })
            fixes.chunked(10).forEach(client::enqueue)

            client.connectAndSend()

            // 1125 m in 90 s is 12.5 m/s, far below the speed limit of the jump rule.
            acks.flatMap { it.rejected } shouldBe emptyList()
            val stored = StoredTrack(client.sessionId)
            stored.fixCount() shouldBe fixes.size.toLong()
            stored.lastCumulativeM() shouldBe (fixes.lengthM() plusOrMinus 0.001)
            stored.lastCumulativeM() shouldBe (2_000.0 plusOrMinus 60.0) // the tunnel is straight, so the total is still 2 km
        }
}
