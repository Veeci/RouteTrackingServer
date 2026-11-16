package veeci.practicing.rts.shared.geo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.testing.geo.offset
import kotlin.math.sin

class DouglasPeuckerTest {
    private val start = GeoPoint(21.0285, 105.8542) // Hoan Kiem lake, Hanoi

    @Test
    fun `TC-8-DP-01 collinear points keep only the two ends`() {
        val line = (0..10).map { start.offset(eastM = it * 100.0) }

        DouglasPeucker.simplify(line, 1.0) shouldContainExactly listOf(line.first(), line.last())
    }

    @Test
    fun `a corner farther than the tolerance stays, and a bump inside it goes`() {
        val corner = start.offset(northM = 500.0)
        val bump = start.offset(northM = 250.0, eastM = 3.0) // 3 m beside the first leg
        val end = corner.offset(eastM = 500.0)

        DouglasPeucker.simplify(listOf(start, bump, corner, end), 5.0) shouldContainExactly listOf(start, corner, end)
        DouglasPeucker.simplify(listOf(start, bump, corner, end), 2.0) shouldContainExactly listOf(start, bump, corner, end)
    }

    @Test
    fun `TC-8-DP-02 every point is within the tolerance of the simplified line, and both ends stay`() =
        runTest {
            val steps = Arb.list(Arb.int(-100..100), 3..300)
            checkAll(200, steps, Arb.numericDouble(1.0, 50.0)) { turns, toleranceM ->
                // A random walk: each step 20 m long, turning by up to ±100 degrees.
                var heading = 0.0
                val track =
                    turns.runningFold(start) { p, turn ->
                        heading += Math.toRadians(turn.toDouble())
                        p.offset(northM = 20 * kotlin.math.cos(heading), eastM = 20 * sin(heading))
                    }

                val simplified = DouglasPeucker.simplify(track, toleranceM)

                simplified.first() shouldBe track.first()
                simplified.last() shouldBe track.last()
                val line = MeasuredPolyline(simplified)
                // The two use different flat frames (one origin, one per segment): allow 1 % plus 1 cm.
                track.forEach { line.project(it).crossTrackM shouldBeLessThanOrEqual toleranceM * 1.01 + 0.01 }
            }
        }

    @Test
    fun `TC-8-DP-03 100 000 points simplify without a stack overflow`() {
        // A zigzag with 5 m teeth: with a 1 m tolerance nearly every point stays, the deepest case for the ranges.
        val track = (0 until 100_000).map { start.offset(northM = it * 1.0, eastM = if (it % 2 == 0) 0.0 else 5.0) }

        DouglasPeucker.simplify(track, 1.0).size shouldBe track.size
        DouglasPeucker.simplify(track, 10.0).size shouldBeLessThan 10
    }

    @Test
    fun `items keep their own data, and a tolerance of 0 or a line of two points changes nothing`() {
        val stamped = (0..4).map { start.offset(eastM = it * 10.0) to it }

        DouglasPeucker.simplify(stamped, 1.0) { it.first }.map { it.second } shouldContainExactly listOf(0, 4)
        DouglasPeucker.simplify(stamped, 0.0) { it.first } shouldBe stamped
        DouglasPeucker.simplify(stamped.take(2), 50.0) { it.first } shouldBe stamped.take(2)
        shouldThrow<IllegalArgumentException> { DouglasPeucker.simplify(listOf(start), -1.0) }
    }
}
