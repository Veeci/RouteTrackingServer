package veeci.practicing.rts.telemetry.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SessionHealthCalculatorTest {
    private val t0 = Instant.parse("2026-11-17T08:00:00Z")

    private fun fixesEvery(
        step: Duration,
        count: Int,
        from: Instant = t0,
        provider: String = "GMS_FUSED",
    ) = (0 until count).map { HealthFix(from + step * it, accuracyM = 5.0, provider) }

    private fun sample(
        at: Instant,
        level: Int = 50,
        charging: Boolean = false,
        motion: String? = "STILL",
    ) = HealthSample(at, level, charging, motion)

    private fun calculate(
        fixes: List<HealthFix>,
        samples: List<HealthSample> = emptyList(),
        rejections: Map<String, Int> = emptyMap(),
        constraints: List<ConstraintChange> = emptyList(),
    ) = SessionHealthCalculator.calculate(HealthInput(fixes, rejections, samples, constraints, defaultInterval = 5.seconds))

    /** Fixes every 5 s for a minute, nothing for [gap], then fixes every 5 s for another minute. */
    private fun withGap(gap: Duration): List<HealthFix> {
        val first = fixesEvery(5.seconds, 13)
        return first + fixesEvery(5.seconds, 13, from = first.last().at + gap)
    }

    @Test
    fun `TC-9-HLT-01 fixes every 5 s have a median interval of 5 s and no gaps`() {
        val health = calculate(fixesEvery(5.seconds, 100))

        health.intervalP50S shouldBe 5.0
        health.intervalP95S shouldBe 5.0
        health.gaps.shouldBeEmpty()
    }

    @Test
    fun `TC-9-HLT-02 an 8-minute gap, still and not charging on both sides, is likely Doze`() {
        val fixes = withGap(8.minutes)
        val start = fixes[12].at

        val health = calculate(fixes, samples = listOf(sample(start - 30.seconds), sample(start + 8.minutes)))

        health.gaps.single() shouldBe Gap(start, durationS = 480.0, expectedIntervalS = 5.0, likelyDoze = true)
    }

    @Test
    fun `TC-9-HLT-03 the same gap while charging is not Doze`() {
        val fixes = withGap(8.minutes)
        val start = fixes[12].at

        val health = calculate(fixes, samples = listOf(sample(start - 30.seconds, charging = true), sample(start + 8.minutes)))

        health.gaps.single().likelyDoze shouldBe false
    }

    @Test
    fun `a gap without motion, without a sample after it, or under 5 minutes is not Doze`() {
        val start = withGap(8.minutes)[12].at

        calculate(withGap(8.minutes), listOf(sample(start, motion = null), sample(start + 8.minutes))).gaps.single().likelyDoze shouldBe
            false
        calculate(withGap(8.minutes), listOf(sample(start))).gaps.single().likelyDoze shouldBe false
        calculate(withGap(4.minutes), listOf(sample(start), sample(start + 4.minutes))).gaps.single().likelyDoze shouldBe false
    }

    @Test
    fun `TC-9-HLT-04 the gap threshold uses the constraint in effect when the gap starts`() {
        val fixes = fixesEvery(90.seconds, 3) // two 90 s intervals
        val oneMinute = ConstraintChange(t0 - 1.minutes, minIntervalMs = 60_000)
        val fiveSeconds = ConstraintChange(t0 + 1.minutes, minIntervalMs = 5_000) // applied during the first interval

        calculate(fixes, constraints = listOf(oneMinute)).gaps.shouldBeEmpty() // 90 s is under 3 × 60 s
        calculate(fixes, constraints = listOf(oneMinute, fiveSeconds)).gaps.map { it.start } shouldBe listOf(t0 + 90.seconds)
        calculate(fixes).gaps.size shouldBe 2 // the 5 s default: 90 s is over 30 s
        calculate(fixes, constraints = listOf(ConstraintChange(t0 - 1.minutes, 0))).gaps.size shouldBe 2 // 0 means the default
    }

    @Test
    fun `TC-9-HLT-05 charging is left out of the drain, and runs are weighted by their length`() {
        val samples =
            listOf(
                // 1 hour from 80 to 70: 10 %/h
                sample(t0, 80),
                sample(t0 + 30.minutes, 75),
                sample(t0 + 60.minutes, 70),
                // charging from 70 to 90: left out
                sample(t0 + 70.minutes, 80, charging = true),
                sample(t0 + 90.minutes, 90, charging = true),
                // 3 hours from 90 to 75: 5 %/h
                sample(t0 + 100.minutes, 90),
                sample(t0 + 280.minutes, 75),
            )

        // (10 × 1 h + 5 × 3 h) / 4 h
        calculate(emptyList(), samples).batteryDrainPctPerHour shouldBe (6.25 plusOrMinus 1e-9)
    }

    @Test
    fun `a run under 10 minutes gives no drain`() {
        calculate(emptyList(), listOf(sample(t0, 80), sample(t0 + 9.minutes, 79))).batteryDrainPctPerHour.shouldBeNull()
    }

    @Test
    fun `TC-9-HLT-06 the accepted ratio and the rejections by reason`() {
        val health = calculate(fixesEvery(5.seconds, 8), rejections = mapOf("POOR_ACCURACY" to 1, "MOCK_LOCATION" to 1))

        health.acceptedRatio shouldBe 0.8
        health.rejectedCount shouldBe 2
        health.rejectionsByReason shouldBe mapOf("MOCK_LOCATION" to 1, "POOR_ACCURACY" to 1)
    }

    @Test
    fun `TC-9-HLT-07 a session with no fixes and no samples gives zeros and nulls`() {
        val health = calculate(emptyList())

        health.fixCount shouldBe 0
        health.acceptedRatio.shouldBeNull()
        health.intervalP50S.shouldBeNull()
        health.accuracyP95M.shouldBeNull()
        health.batteryDrainPctPerHour.shouldBeNull()
        health.primaryProvider.shouldBeNull()
        health.gaps.shouldBeEmpty()
    }

    @Test
    fun `TC-9-HLT-08 the provider mix of 70 GMS and 30 AOSP fixes`() {
        val fixes = fixesEvery(1.seconds, 70) + fixesEvery(1.seconds, 30, from = t0 + 70.seconds, provider = "AOSP_GPS")

        val health = calculate(fixes)

        health.providerMix shouldBe mapOf("AOSP_GPS" to 0.3, "GMS_FUSED" to 0.7)
        health.primaryProvider shouldBe "GMS_FUSED"
    }

    @Test
    fun `a tie between providers goes to the first name in alphabetical order`() {
        val fixes =
            fixesEvery(1.seconds, 2, provider = "GMS_FUSED") + fixesEvery(1.seconds, 2, from = t0 + 2.seconds, provider = "AOSP_GPS")

        calculate(fixes).primaryProvider shouldBe "AOSP_GPS"
    }
}
