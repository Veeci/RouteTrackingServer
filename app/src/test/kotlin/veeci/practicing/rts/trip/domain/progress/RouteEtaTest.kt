package veeci.practicing.rts.trip.domain.progress

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RouteEtaTest {
    private val eta = RouteEta()

    /** 3 km planned in 300 s: 10 m/s on average. */
    private val distanceM = 3_000.0
    private val durationS = 300.0

    @Test
    fun `TC-7-ETA-01 a driver at the planned speed gets k 1 and the route's own time for the distance left`() {
        eta.factor(10.0, 10.0) shouldBe 1.0
        eta.etaS(distanceM, durationS, remainingM = 1_200.0, observedSpeedMps = 10.0) shouldBe (120.0 plusOrMinus 1e-9)
    }

    @Test
    fun `TC-7-ETA-02 a driver at half the planned speed gets twice the route's time`() {
        eta.etaS(distanceM, durationS, remainingM = 1_200.0, observedSpeedMps = 5.0) shouldBe (240.0 plusOrMinus 1e-9)
    }

    @Test
    fun `TC-7-ETA-03 extreme speeds are clamped to k between 0_5 and 3`() {
        eta.factor(10.0, 100.0) shouldBe 0.5
        eta.factor(10.0, 0.5) shouldBe 3.0
        eta.factor(10.0, 0.0) shouldBe 3.0
    }

    @Test
    fun `before any speed is known the plan counts as right`() {
        eta.etaS(distanceM, durationS, remainingM = 1_200.0, observedSpeedMps = null) shouldBe (120.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a route of length or duration zero has nothing left to drive`() {
        eta.etaS(0.0, 0.0, remainingM = 0.0, observedSpeedMps = 10.0) shouldBe 0.0
    }
}
