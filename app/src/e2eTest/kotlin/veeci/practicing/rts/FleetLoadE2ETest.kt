package veeci.practicing.rts

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.simulator.GpxReader
import veeci.practicing.rts.simulator.fleet.FleetOptions
import veeci.practicing.rts.simulator.fleet.FleetRun
import veeci.practicing.rts.testing.socketApp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * TC-11-LOAD-01, small: `simulator fleet` with 50 drivers and 2 dashboards against one instance whose fleet tick is
 * 100 ms (test.conf). It checks only the delta cadence: every dashboard gets a `fleet_delta` every tick, p95 within
 * 100 ms ± 50 ms. The full run (1 000 drivers, 20 dashboards, two instances) is manual: docs/testing/load-11.md.
 */
@Testcontainers(disabledWithoutDocker = true)
class FleetLoadE2ETest {
    @Test
    fun `TC-11-LOAD-01 small fleet, every dashboard gets a delta every tick while 50 drivers send`() =
        // 50 token requests, 2 tickets and 52 socket upgrades from one address: more than the default 300 a minute.
        socketApp(env = mapOf("HTTP_RATELIMITPERMINUTE" to "100000")) {
            val options =
                FleetOptions(
                    baseUrls = listOf(baseUrl),
                    drivers = DRIVERS,
                    dashboards = DASHBOARDS,
                    duration = 10.seconds,
                    fixEvery = 1.seconds,
                    batchSize = 2,
                    expectedGap = 100.milliseconds,
                    tolerance = 50.milliseconds,
                )
            val route = GpxReader.load("routes/hanoi_loop.gpx")

            val report = FleetRun.httpClient(DRIVERS + DASHBOARDS).use { FleetRun(it, options, route).run() }

            withClue(report.lines().joinToString("\n")) {
                report.stats.connectedDrivers.get() shouldBe DRIVERS
                report.stats.accepted.get() shouldBeGreaterThan DRIVERS
                report.stats.connectedDashboards.get() shouldBe DASHBOARDS
                // About 9 s of ticks per dashboard after the start-up second; half of that rules out a stalled socket.
                report.gaps.count shouldBeGreaterThan DASHBOARDS * 45
                report.cadenceOk shouldBe true
            }
            report.lines().forEach(::println)
        }

    private companion object {
        const val DRIVERS = 50
        const val DASHBOARDS = 2
    }
}
