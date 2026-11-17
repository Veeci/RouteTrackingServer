package veeci.practicing.rts.platform.observability

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * runBlocking, not runTest: the checks run on real IO threads, but runTest's virtual clock jumps past the
 * registry's 2 s deadline as soon as the test coroutine waits. A check still running at that moment counts as DOWN.
 */
class HealthRegistryTest {
    private fun indicator(
        name: String,
        status: HealthStatus,
        critical: Boolean = true,
    ) = object : HealthIndicator {
        override val name = name
        override val critical = critical

        override fun check() = status
    }

    @Test
    fun `TC-10-RED-02 a non-critical check that is DOWN is reported but leaves the instance ready`() =
        runBlocking<Unit> {
            HealthRegistry(listOf(indicator("db", HealthStatus.UP), indicator("redis", HealthStatus.DOWN, critical = false))).use {
                it.readiness() shouldBe HealthReport(HealthStatus.UP, mapOf("db" to HealthStatus.UP, "redis" to HealthStatus.DOWN))
            }
        }

    @Test
    fun `a critical check that is DOWN makes the instance unready`() =
        runBlocking<Unit> {
            HealthRegistry(listOf(indicator("db", HealthStatus.DOWN), indicator("redis", HealthStatus.UP, critical = false))).use {
                it.readiness().status shouldBe HealthStatus.DOWN
            }
        }
}
