package veeci.practicing.rts.trip.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ArrivalRulesTest {
    private val rules = ArrivalRules()
    private val t0 = Instant.parse("2026-11-12T08:00:00Z")

    /** Folds one fix every 5 s through the rules; returns the distances that raised arriving and arrived. */
    private fun run(
        vararg meters: Double,
        speedMps: Double? = 8.0,
        everySeconds: Int = 5,
    ): Pair<List<Double>, List<Double>> {
        val arriving = mutableListOf<Double>()
        val arrived = mutableListOf<Double>()
        meters.foldIndexed(ArrivalState()) { i, state, m ->
            val outcome = rules.evaluate(state, ArrivalSample(m, speedMps, t0 + (i * everySeconds).seconds))
            outcome.arriving?.let { arriving += it.remainingM }
            outcome.arrived?.let { arrived += it.remainingM }
            outcome.state
        }
        return arriving to arrived
    }

    @Test
    fun `TC-4-ARV-01 the second fix in a row within 200 m raises arriving once`() {
        run(250.0, 190.0, 180.0, 170.0).first shouldBe listOf(180.0)
    }

    @Test
    fun `TC-4-ARV-02 flickering around 200 m never raises arriving`() {
        run(190.0, 210.0, 190.0, 210.0).first.shouldBeEmpty()
    }

    @Test
    fun `TC-4-ARV-03 after arriving, fixes up to 300 m do not reset it`() {
        run(190.0, 180.0, 190.0, 250.0, 190.0, 180.0).first shouldBe listOf(180.0)
    }

    @Test
    fun `TC-4-ARV-04 two fixes in a row beyond 300 m reset arriving, so a new approach raises it again`() {
        run(190.0, 180.0, 310.0, 320.0, 190.0, 170.0).first shouldBe listOf(180.0, 170.0)
    }

    @Test
    fun `one fix beyond 300 m does not reset arriving`() {
        run(190.0, 180.0, 310.0, 190.0, 180.0).first shouldBe listOf(180.0)
    }

    @Test
    fun `TC-4-ARD-01 the third fix in a row within 30 m is arrived`() {
        run(29.0, 20.0, 10.0, 5.0).second shouldBe listOf(10.0, 5.0) // the caller stops at the first one
    }

    @Test
    fun `a fix beyond 30 m breaks the arrived count`() {
        run(29.0, 20.0, 40.0, 10.0, 5.0).second.shouldBeEmpty()
    }

    @Test
    fun `TC-4-ARD-02 within 30 m and slower than 1,5 m per s for 10 s is arrived`() {
        run(25.0, 26.0, speedMps = 0.5, everySeconds = 10).second shouldBe listOf(26.0)
    }

    @Test
    fun `slow for less than 10 s is not arrived yet`() {
        run(25.0, 26.0, speedMps = 0.5, everySeconds = 9).second.shouldBeEmpty()
    }

    @Test
    fun `without a measured speed only the fixes in a row count`() {
        run(25.0, 26.0, speedMps = null, everySeconds = 30).second.shouldBeEmpty()
    }

    @Test
    fun `a fix at or before the last one counted changes nothing (a batch delivered twice)`() {
        val first = rules.evaluate(ArrivalState(), ArrivalSample(190.0, 8.0, t0))
        val repeat = rules.evaluate(first.state, ArrivalSample(190.0, 8.0, t0))

        repeat.state shouldBe first.state
        repeat.arriving shouldBe null
    }
}
