package veeci.practicing.rts.simulator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GpxReaderTest {
    @Test
    fun `reads every track point with its position and time`() {
        val points = GpxReader.load("routes/straight_2km.gpx")

        points.size shouldBe 161
        points.first() shouldBe TrackPoint(10.7725, 106.698, Instant.parse("2026-09-30T02:00:00Z"), desc = null)
        points.map { it.time }.shouldBeSorted()
    }

    @Test
    fun `reads the desc of a point, which marks the injected GPS errors`() {
        val spikes =
            GpxReader
                .load("routes/gps_jump.gpx")
                .withIndex()
                .filter { it.value.desc == "spike" }
                .map { it.index }

        spikes shouldBe listOf(20, 41)
    }

    @Test
    fun `fixes keep the route's spacing and end now`() {
        val now = Instant.parse("2026-10-07T08:00:00Z")

        val fixes = GpxReader.load("routes/straight_2km.gpx").toFixes(now)

        fixes.last().recordedAt shouldBe now
        fixes.first().recordedAt shouldBe now - 160.seconds
    }

    @Test
    fun `a missing route is reported with its name`() {
        shouldThrow<IllegalArgumentException> { GpxReader.load("routes/nowhere.gpx") }.message shouldBe
            "No GPX file or resource at 'routes/nowhere.gpx'"
    }
}
