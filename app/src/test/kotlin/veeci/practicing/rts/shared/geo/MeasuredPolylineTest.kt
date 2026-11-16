package veeci.practicing.rts.shared.geo

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.testing.geo.offset
import veeci.practicing.rts.testing.geo.polylineFrom

class MeasuredPolylineTest {
    private val start = GeoPoint(21.0285, 105.8542) // Hoan Kiem lake, Hanoi

    /** 200 m east, then 300 m north, then 100 m west: 600 m in three segments. */
    private val route = polylineFrom(start, 0.0 to 200.0, 300.0 to 0.0, 0.0 to -100.0)

    @Test
    fun `the length and the distance to each point add up the segments`() {
        route.segmentCount shouldBe 3
        route.lengthM shouldBe (600.0 plusOrMinus 0.5)
        route.distanceToPointM(2) shouldBe (500.0 plusOrMinus 0.5)
    }

    @Test
    fun `TC-7-GEO-01 a point in the middle of a segment projects to fraction 0_5 with no cross-track`() {
        val p = route.project(start.offset(eastM = 100.0))

        p.segmentIndex shouldBe 0
        p.fraction shouldBe (0.5 plusOrMinus 1e-3)
        p.crossTrackM shouldBe (0.0 plusOrMinus 0.01)
        p.alongM shouldBe (100.0 plusOrMinus 0.5)
    }

    @Test
    fun `TC-7-GEO-01 on a later segment alongM is the earlier segments plus the part of this one`() {
        val p = route.project(start.offset(northM = 150.0, eastM = 200.0))

        p.segmentIndex shouldBe 1
        p.alongM shouldBe (350.0 plusOrMinus 0.5)
    }

    @Test
    fun `TC-7-GEO-02 a point beyond a segment's end projects onto that end`() {
        val p = route.projectOnSegment(start.offset(eastM = 260.0), 0)

        p.fraction shouldBe 1.0
        p.alongM shouldBe (200.0 plusOrMinus 0.5)
        p.crossTrackM shouldBe (60.0 plusOrMinus 0.5)
    }

    @Test
    fun `TC-7-GEO-03 a point 40 m beside a segment has a cross-track of 40 m`() {
        val p = route.projectOnSegment(start.offset(northM = -40.0, eastM = 80.0), 0)

        p.crossTrackM shouldBe (40.0 plusOrMinus 0.5)
        p.alongM shouldBe (80.0 plusOrMinus 0.5)
    }

    @Test
    fun `each segment knows its bearing`() {
        route.projectOnSegment(start, 0).segmentBearingDeg!! shouldBe (90.0 plusOrMinus 0.01)
        route.projectOnSegment(start, 1).segmentBearingDeg!! shouldBe (0.0 plusOrMinus 0.01)
        route.projectOnSegment(start, 2).segmentBearingDeg!! shouldBe (270.0 plusOrMinus 0.01)
    }

    @Test
    fun `the search range limits which segments may match`() {
        // The end of the last segment (100 m west of the corner) is closest to segment 2, but only 0..1 may match.
        val p = route.project(start.offset(northM = 300.0, eastM = 100.0), 0..1)

        p.segmentIndex shouldBe 1
    }

    @Test
    fun `a single point becomes one segment of length zero`() {
        val dot = MeasuredPolyline(listOf(start))

        dot.segmentCount shouldBe 1
        dot.lengthM shouldBe 0.0
        dot.project(start.offset(northM = 10.0)).crossTrackM shouldBe (10.0 plusOrMinus 0.01)
        dot.project(start).segmentBearingDeg shouldBe null
    }

    @Test
    fun `TC-7-GEO-04 a point made on the polyline projects back to the distance that made it`() =
        runTest {
            checkAll(Arb.double(0.0..600.0, includeNaNs = false)) { d ->
                route.project(route.pointAt(d)).alongM shouldBe (d plusOrMinus 0.5)
            }
        }

    @Test
    fun `TC-7-GEO-05 alongM stays between 0 and the length for any nearby point`() =
        runTest {
            checkAll(Arb.double(-2_000.0..2_000.0, includeNaNs = false), Arb.double(-2_000.0..2_000.0, includeNaNs = false)) { n, e ->
                route.project(start.offset(n, e)).alongM.shouldBeBetween(0.0, route.lengthM, 1e-9)
            }
        }
}
