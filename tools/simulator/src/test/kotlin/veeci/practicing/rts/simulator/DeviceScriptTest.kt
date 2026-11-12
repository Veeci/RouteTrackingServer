package veeci.practicing.rts.simulator

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.protocol.FixBatch
import veeci.practicing.rts.protocol.FixDto
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DeviceScriptTest {
    private val start = Instant.parse("2026-11-12T08:00:00Z")

    /** A batch of one fix at [s] seconds of route time. */
    private fun batchAt(s: Int) = FixBatch(1, listOf(FixDto(10.0, 106.0, accuracyM = 5.0, recordedAt = start + s.seconds)))

    @Test
    fun `a status goes out every 30 s of route time, with the battery draining per minute`() {
        val script = DeviceScript(start, batteryPercent = 20, drainPerMinute = 2.0)

        val levels = (0..120 step 10).map { s -> script.statusBefore(batchAt(s))?.battery?.levelPercent }

        levels shouldBe listOf(20, null, null, 19, null, null, 18, null, null, 17, null, null, 16)
    }

    @Test
    fun `the battery never goes below 0`() {
        DeviceScript(start, batteryPercent = 1, drainPerMinute = 10.0).statusBefore(batchAt(600))!!.battery.levelPercent shouldBe 0
    }

    @Test
    fun `the pause happens once, at the first batch at or after its route time`() {
        val script = DeviceScript(start, pauseAt = 120.seconds, pauseFor = 70.seconds)

        listOf(60, 110, 125, 130).map { script.pauseBefore(batchAt(it)) } shouldBe
            listOf(Duration.ZERO, Duration.ZERO, 70.seconds, Duration.ZERO)
    }

    @Test
    fun `pause-at reads route seconds and real seconds`() {
        DeviceScript.parsePause("120:70") shouldBe (120.seconds to 70.seconds)
    }
}
