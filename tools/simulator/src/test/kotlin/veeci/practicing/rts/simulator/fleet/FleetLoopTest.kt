package veeci.practicing.rts.simulator.fleet

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.simulator.GpxReader
import veeci.practicing.rts.simulator.TrackPoint
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FleetLoopTest {
    private val t0 = Instant.parse("2026-11-17T08:00:00Z")
    private val square =
        listOf(0.0 to 0.0, 0.0 to 0.001, 0.001 to 0.001, 0.001 to 0.0, 0.0 to 0.0).mapIndexed { i, (lat, lng) ->
            TrackPoint(21.0 + lat, 105.8 + lng, t0 + i.seconds, null)
        }

    @Test
    fun `batches walk the loop from the start point, wrap around, and skip the closing point`() {
        val loop = FleetLoop(square, start = 2, northM = 0.0, eastM = 0.0, fixEvery = 1.seconds, batchSize = 3)

        val first = loop.next(t0)
        val second = loop.next(t0 + 3.seconds)

        first.map { it.lat to it.lng } shouldBe listOf(square[2], square[3], square[0]).map { it.lat to it.lng }
        second.map { it.lat to it.lng } shouldBe listOf(square[1], square[2], square[3]).map { it.lat to it.lng }
    }

    @Test
    fun `fixes are fix-every apart, the last at now, and times never go back`() {
        val loop = FleetLoop(square, start = 0, northM = 0.0, eastM = 0.0, fixEvery = 1.seconds, batchSize = 3)

        loop.next(t0).map { it.recordedAt } shouldBe listOf(t0 - 2.seconds, t0 - 1.seconds, t0)
        // A clock that went back: each fix still comes after the one before.
        loop.next(t0 - 5.seconds).map { it.recordedAt } shouldBe listOf(t0 + 1.milliseconds, t0 + 2.milliseconds, t0 + 3.milliseconds)
    }

    @Test
    fun `a shift moves every point by the same metres`() {
        val loop = FleetLoop(square, start = 0, northM = 1_113.2, eastM = 0.0, fixEvery = 1.seconds, batchSize = 1)

        loop.next(t0).single().lat shouldBe (square[0].lat + 0.01 plusOrMinus 1e-9)
    }

    @Test
    fun `the Hanoi loop is in Hanoi, and a random loop starts somewhere on it within the spread`() {
        val hanoi = GpxReader.load("routes/hanoi_loop.gpx")
        val loop = FleetLoop.random(hanoi, spreadM = 1_000.0, fixEvery = 1.seconds, batchSize = 1, random = Random(7))

        hanoi.all { it.lat in 21.0..21.1 && it.lng in 105.8..105.9 } shouldBe true
        val fix = loop.next(t0).single()
        (fix.lat in 20.98..21.05 && fix.lng in 105.83..105.88) shouldBe true
    }

    @Test
    fun `percentiles are by nearest rank`() {
        val spread = Spread.of((1..100).map { it.milliseconds }.shuffled())

        spread shouldBe Spread(100, 50.milliseconds, 95.milliseconds, 100.milliseconds)
        Spread.of(emptyList()).count shouldBe 0
    }
}
