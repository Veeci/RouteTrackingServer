package veeci.practicing.rts.simulator

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DetourTest {
    private val start = Instant.parse("2026-11-16T08:00:00Z")

    /** A point every second, all at the same place, so only the detour moves them. */
    private val points = (0..100).map { TrackPoint(21.0, 105.8, start + it.seconds, null) }

    private fun eastM(point: TrackPoint) = (point.lng - 105.8) * 111_195.0 * cos(Math.toRadians(21.0))

    @Test
    fun `points inside the detour move east, ramping in and out, and the rest stay`() {
        val moved = Detour.parse("30:40:80").applyTo(points)

        eastM(moved[29]) shouldBe 0.0
        eastM(moved[35]) shouldBe (40.0 plusOrMinus 0.01) // halfway up the 10 s ramp
        eastM(moved[50]) shouldBe (80.0 plusOrMinus 0.01)
        eastM(moved[70]) shouldBe (0.0 plusOrMinus 0.01)
        eastM(moved[71]) shouldBe 0.0
    }

    @Test
    fun `no step is faster than the server's 70 m per s jump limit`() {
        val moved = Detour.parse("10:40:300").applyTo(points)

        moved.zipWithNext().forEach { (a, b) -> kotlin.math.abs(eastM(b) - eastM(a)) shouldBeLessThan 70.0 }
    }
}
