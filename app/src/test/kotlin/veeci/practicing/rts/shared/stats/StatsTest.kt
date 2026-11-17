package veeci.practicing.rts.shared.stats

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsTest {
    private val oneToHundred = (1..100).map { it.toDouble() }

    @Test
    fun `TC-9-STAT-01 percentiles of 1 to 100 by linear interpolation`() {
        percentile(oneToHundred, 50.0) shouldBe (50.5 plusOrMinus 1e-9)
        percentile(oneToHundred, 95.0) shouldBe (95.05 plusOrMinus 1e-9)
        percentile(oneToHundred, 0.0) shouldBe 1.0
        percentile(oneToHundred, 100.0) shouldBe 100.0
        percentile(oneToHundred.shuffled(), 50.0) shouldBe (50.5 plusOrMinus 1e-9) // the order does not matter
    }

    @Test
    fun `TC-9-STAT-02 one value is every percentile, and no values give null`() {
        percentile(listOf(7.0), 95.0) shouldBe 7.0
        percentile(emptyList(), 50.0).shouldBeNull()
        shouldThrow<IllegalArgumentException> { percentile(oneToHundred, 101.0) }
    }

    @Test
    fun `TC-9-STAT-03 a steady fall from 100 to 90 over 2 hours has a slope of -5 per hour`() {
        val points = (0..12).map { Point(x = it * 10 / 60.0, y = 100.0 - it * 10 / 12.0) } // every 10 minutes

        slope(points) shouldBe (-5.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a slope needs two points with different x`() {
        slope(listOf(Point(0.0, 1.0))).shouldBeNull()
        slope(listOf(Point(1.0, 1.0), Point(1.0, 2.0))).shouldBeNull()
    }
}
