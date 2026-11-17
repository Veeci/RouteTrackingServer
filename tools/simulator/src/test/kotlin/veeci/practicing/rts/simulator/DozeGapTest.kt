package veeci.practicing.rts.simulator

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DozeGapTest {
    private val start = Instant.parse("2026-11-17T08:00:00Z")
    private val points = (0..4).map { TrackPoint(21.0 + it * 1e-4, 105.8, start + (it * 30).seconds, null) }

    @Test
    fun `points from the gap on move later and keep their position`() {
        val moved = DozeGap(at = 60.seconds, length = 6.minutes).applyTo(points)

        moved.map { (it.time - start).inWholeSeconds } shouldBe listOf(0, 30, 420, 450, 480)
        moved.map { it.lat } shouldBe points.map { it.lat }
    }

    @Test
    fun `statuses near the gap say STILL, the others keep the motion they were given`() {
        val gap = DozeGap(at = 120.seconds, length = 6.minutes)
        val script = DeviceScript(start, motion = "IN_VEHICLE", dozeGap = gap)

        fun motionAt(s: Int) =
            script.statusBefore(FixBatch(1, listOf(FixDto(21.0, 105.8, accuracyM = 5.0, recordedAt = start + s.seconds))))?.motion

        listOf(0, 90, 480, 600).map(::motionAt) shouldBe listOf("IN_VEHICLE", "STILL", "STILL", "IN_VEHICLE")
    }

    @Test
    fun `doze-gap reads route seconds and seconds`() {
        DozeGap.parse("60:360").let { (it.at to it.length) shouldBe (60.seconds to 6.minutes) }
    }
}
