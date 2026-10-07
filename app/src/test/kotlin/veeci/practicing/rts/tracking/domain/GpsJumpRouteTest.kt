package veeci.practicing.rts.tracking.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.simulator.GpxReader
import veeci.practicing.rts.tracking.domain.Readings.NOW

/** The pipeline against a recorded route with known GPS errors, instead of hand-made points. */
class GpsJumpRouteTest {
    @Test
    fun `TC-2-PIP-16 exactly the points marked as spikes in gps_jump gpx are rejected as jumps`() {
        val points = GpxReader.load("routes/gps_jump.gpx")
        val shift = NOW - points.last().time
        val batch = points.map { Readings.reading(at = it.time + shift).copy(lat = it.lat, lng = it.lng) }
        val spikes = points.withIndex().filter { it.value.desc == "spike" }.map { it.index }

        val result = FixPipeline(PipelineConfig()).process(batch, PipelineContext(NOW, previous = null))

        result.rejected shouldBe spikes.map { Rejection(it, RejectionReason.IMPLAUSIBLE_JUMP) }
    }
}
