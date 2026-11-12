package veeci.practicing.rts.trip.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class BatteryRulesTest {
    private val rules = BatteryRules()
    private val t0 = Instant.parse("2026-11-12T08:00:00Z")

    private data class Reading(
        val level: Int,
        val charging: Boolean = false,
        val profile: String? = null,
    )

    /** Folds one status every 30 s through the rule; returns the levels that raised the notice. */
    private fun run(vararg readings: Reading): List<Int> {
        val raised = mutableListOf<Int>()
        readings.foldIndexed(BatteryState()) { i, state, r ->
            val outcome = rules.evaluate(state, BatterySample(r.level, r.charging, r.profile, t0 + (i * 30).seconds))
            outcome.low?.let { raised += it.levelPercent }
            outcome.state
        }
        return raised
    }

    @Test
    fun `TC-4-BAT-01 going from 20 to 15 percent while not charging raises the notice once`() {
        run(Reading(20), Reading(15)) shouldBe listOf(15)
    }

    @Test
    fun `TC-4-BAT-02 15, 14 and 13 percent still raise only one notice`() {
        run(Reading(15), Reading(14), Reading(13)) shouldBe listOf(15)
    }

    @Test
    fun `TC-4-BAT-03 plugging in resets it, so unplugging at 14 percent raises a new notice`() {
        run(Reading(14), Reading(14, charging = true), Reading(14)) shouldBe listOf(14, 14)
    }

    @Test
    fun `TC-4-BAT-04 a CRITICAL profile is low at any level, even while charging`() {
        run(Reading(30, charging = true, profile = "CRITICAL")) shouldBe listOf(30)
    }

    @Test
    fun `between 16 and 24 percent the notice neither fires nor resets`() {
        run(Reading(14), Reading(20), Reading(14)) shouldBe listOf(14)
    }

    @Test
    fun `25 percent resets it`() {
        run(Reading(14), Reading(25), Reading(14)) shouldBe listOf(14, 14)
    }

    @Test
    fun `a low level while charging is not low`() {
        run(Reading(10, charging = true)).shouldBeEmpty()
    }

    @Test
    fun `a status at or before the last one counted changes nothing`() {
        val first = rules.evaluate(BatteryState(), BatterySample(10, false, null, t0))
        val repeat = rules.evaluate(first.state.copy(announced = false), BatterySample(10, false, null, t0))

        repeat.low shouldBe null
    }
}
