package veeci.practicing.rts

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.koin.test.verify.verify
import veeci.practicing.rts.testing.TestConfig

class WiringTest {
    @Test
    fun `TC-1-DI-01 every definition in the app graph can be built`() {
        appModule(TestConfig.load(), SimpleMeterRegistry()).verify()
    }
}
