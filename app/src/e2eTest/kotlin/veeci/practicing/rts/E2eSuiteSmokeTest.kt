package veeci.practicing.rts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.protocol.PROTOCOL_VERSION

/** Proves the e2e suite sees the app and the simulator's protocol. Real journeys start in phase 2. */
class E2eSuiteSmokeTest {
    @Test
    fun `e2e suite runs with protocol on the classpath`() {
        PROTOCOL_VERSION shouldBe 1
    }
}
