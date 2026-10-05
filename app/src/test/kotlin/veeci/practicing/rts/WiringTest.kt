package veeci.practicing.rts

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.koin.test.verify.verify
import veeci.practicing.rts.platform.config.WsConfig
import veeci.practicing.rts.testing.TestConfig

class WiringTest {
    @Test
    fun `TC-1-DI-01 every definition in the app graph can be built`() {
        // Config sections are handed to constructors by the module function, not built by Koin: declare them as given.
        appModule(TestConfig.load(), SimpleMeterRegistry()).verify(extraTypes = listOf(WsConfig::class))
    }
}
