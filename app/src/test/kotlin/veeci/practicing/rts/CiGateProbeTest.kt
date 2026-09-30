package veeci.practicing.rts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** TC-0-CI-01 probe: fails on purpose to prove a red check blocks merging. Reverted right after. */
class CiGateProbeTest {
    @Test
    fun `deliberately failing test`() {
        1 shouldBe 2
    }
}
