package veeci.practicing.rts.trip.domain.summary

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Haversine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LegSummarizerTest {
    private val t0 = Instant.parse("2026-11-16T08:00:00Z")
    private val start = GeoPoint(21.0285, 105.8542)

    /** Metres per degree of latitude on the sphere Haversine uses. */
    private val metresPerDegree = Haversine.EARTH_RADIUS_M * Math.PI / 180

    /** A sample [northM] metres north of [start], [after] the first one. */
    private fun sample(
        northM: Double,
        after: Duration,
        speedMps: Double? = null,
    ) = TrackSample(GeoPoint(start.lat + northM / metresPerDegree, start.lng), t0 + after, speedMps)

    /** One sample a second at [speedMps] for [seconds] seconds, starting [offset] and [fromM] metres in. */
    private fun drive(
        seconds: Int,
        speedMps: Double,
        offset: Duration = Duration.ZERO,
        fromM: Double = 0.0,
    ) = (0..seconds).map { sample(fromM + it * speedMps, offset + it.seconds, speedMps) }

    private fun summarize(
        samples: List<TrackSample>,
        to: Instant = samples.lastOrNull()?.at ?: t0,
    ) = LegSummarizer(t0, to).apply { samples.forEach(::add) }.result()

    @Test
    fun `TC-8-SUM-01 3 km at 10 m per second`() {
        val leg = summarize(drive(seconds = 300, speedMps = 10.0))

        leg.distanceM shouldBe (3_000.0 plusOrMinus 30.0)
        leg.movingTimeS shouldBe 300
        leg.durationS shouldBe 300
        leg.avgSpeedMps shouldBe (10.0 plusOrMinus 0.1)
        leg.pointCount shouldBe 301
    }

    @Test
    fun `TC-8-SUM-02 a 5-minute stop counts in the duration and not in the moving time`() {
        val before = drive(seconds = 60, speedMps = 10.0)
        val stopped = (1..300).map { sample(600.0, 60.seconds + it.seconds, 0.0) }
        val after = drive(seconds = 60, speedMps = 10.0, offset = 360.seconds, fromM = 600.0).drop(1)

        val leg = summarize(before + stopped + after)

        leg.durationS shouldBe 420
        leg.movingTimeS shouldBe 120
        leg.distanceM shouldBe (1_200.0 plusOrMinus 12.0)
    }

    @Test
    fun `TC-8-SUM-03 one 60 m per second spike does not become the max speed`() {
        val samples = drive(seconds = 20, speedMps = 10.0).toMutableList()
        samples[10] = samples[10].copy(speedMps = 60.0)

        summarize(samples).maxSpeedMps shouldBe 10.0
    }

    @Test
    fun `TC-8-SUM-04 a 10-minute gap adds no distance and no moving time`() {
        val before = drive(seconds = 30, speedMps = 10.0)
        // The phone comes back 10 minutes later, 5 km further.
        val after = drive(seconds = 30, speedMps = 10.0, offset = 40.minutes, fromM = 5_300.0)

        val leg = summarize(before + after)

        leg.distanceM shouldBe (600.0 plusOrMinus 6.0)
        leg.movingTimeS shouldBe 60
    }

    @Test
    fun `TC-8-SUM-06 no samples give zeros without dividing by zero`() {
        val leg = LegSummarizer(t0, t0 + 10.minutes).result()

        leg shouldBe LegSummary(0.0, 600, 0, 0.0, 0.0, 0, t0, t0 + 10.minutes)
    }

    @Test
    fun `fewer than three speeds give the lowest one as the max`() {
        summarize(listOf(sample(0.0, Duration.ZERO, 50.0), sample(10.0, 1.seconds, 10.0))).maxSpeedMps shouldBe 10.0
    }

    @Test
    fun `a sample without a speed uses the speed of its segment`() {
        val leg = summarize((0..5).map { sample(it * 8.0, it.seconds) })

        leg.maxSpeedMps shouldBe (8.0 plusOrMinus 0.01)
    }
}
