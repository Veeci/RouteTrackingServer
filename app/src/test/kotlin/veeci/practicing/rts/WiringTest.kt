package veeci.practicing.rts

import org.junit.jupiter.api.Test
import org.koin.test.verify.verify

class WiringTest {
    @Test
    fun `TC-1-DI-01 every definition in the app graph can be built`() {
        appModule().verify()
    }
}
