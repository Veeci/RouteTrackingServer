package veeci.practicing.rts

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.koin.test.verify.verify
import veeci.practicing.rts.platform.config.WsConfig
import veeci.practicing.rts.protocol.LimitsDto
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.tracking.domain.PipelineConfig
import kotlin.time.Duration

class WiringTest {
    @Test
    fun `TC-1-DI-01 every definition in the app graph can be built`() {
        // Values built from config are handed to constructors by the module functions, not built by Koin:
        // declare their types as given.
        appModule(TestConfig.load(), SimpleMeterRegistry()).verify(
            extraTypes = listOf(WsConfig::class, PipelineConfig::class, LimitsDto::class, Duration::class, Int::class),
        )
    }
}
