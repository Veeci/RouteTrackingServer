package veeci.practicing.rts.simulator.routes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class RouteLoadReportTest {
    private val limit = 150.milliseconds

    @Test
    fun `a run passes with no errors and p95 under the limit, and 422 answers are not errors`() {
        val report = RouteLoadReport(limit)
        (1..100).forEach { report.ok(it.milliseconds) }
        report.unroutable("NO_ROAD_NEARBY")

        report.spread.p50 shouldBe 50.milliseconds
        report.spread.p95 shouldBe 95.milliseconds
        report.spread.max shouldBe 100.milliseconds
        report.passed shouldBe true
        report.lines().joinToString("\n") shouldContain "NO_ROAD_NEARBY=1"
    }

    @Test
    fun `one error fails the run`() {
        val report = RouteLoadReport(limit)
        repeat(10) { report.ok(5.milliseconds) }
        report.error("HTTP 503")

        report.errorCount shouldBe 1
        report.passed shouldBe false
    }

    @Test
    fun `a p95 of 150 ms or more fails the run, and so does a run with no answers`() {
        val slow = RouteLoadReport(limit)
        repeat(20) { slow.ok(150.milliseconds) }

        slow.passed shouldBe false
        RouteLoadReport(limit).passed shouldBe false
    }
}
