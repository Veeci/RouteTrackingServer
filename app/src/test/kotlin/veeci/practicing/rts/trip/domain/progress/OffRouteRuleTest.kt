package veeci.practicing.rts.trip.domain.progress

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OffRouteRuleTest {
    private val rule = OffRouteRule()

    /** Folds fixes of (cross-track, accuracy) through the rule and returns the count after each. */
    private fun counts(vararg fixes: Pair<Double, Double?>): List<Int> =
        fixes.runningFold(0) { count, (crossTrackM, accuracyM) -> rule.count(count, crossTrackM, accuracyM) }.drop(1)

    @Test
    fun `TC-7-OFF-01 three fixes 60 m off with accuracy 10 make the driver off-route`() {
        val after = counts(60.0 to 10.0, 60.0 to 10.0, 60.0 to 10.0)

        after shouldBe listOf(1, 2, 3)
        rule.isOffRoute(after.last()) shouldBe true
        rule.isOffRoute(after[1]) shouldBe false
    }

    @Test
    fun `TC-7-OFF-02 a fix back on the route resets the count`() {
        val after = counts(60.0 to 10.0, 60.0 to 10.0, 20.0 to 10.0, 60.0 to 10.0, 60.0 to 10.0)

        after shouldBe listOf(1, 2, 0, 1, 2)
        after.none { rule.isOffRoute(it) } shouldBe true
    }

    @Test
    fun `TC-7-OFF-03 fixes 60 m off but with accuracy 45 never count`() {
        counts(60.0 to 45.0, 60.0 to 45.0, 60.0 to 45.0) shouldBe listOf(0, 0, 0)
    }

    @Test
    fun `TC-7-OFF-04 between 30 and 50 m the count neither grows nor resets`() {
        counts(60.0 to 10.0, 40.0 to 10.0, 45.0 to 10.0, 60.0 to 10.0) shouldBe listOf(1, 1, 1, 2)
    }

    @Test
    fun `a fix of unknown accuracy does not count, but a near one still resets`() {
        counts(60.0 to 10.0, 60.0 to null, 10.0 to null) shouldBe listOf(1, 1, 0)
    }
}
