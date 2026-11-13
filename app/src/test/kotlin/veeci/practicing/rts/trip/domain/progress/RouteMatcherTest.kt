package veeci.practicing.rts.trip.domain.progress

import io.kotest.matchers.collections.shouldBeSortedDescending
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.MeasuredPolyline
import veeci.practicing.rts.testing.geo.offset
import veeci.practicing.rts.testing.geo.polylineFrom
import veeci.practicing.rts.testing.geo.track

class RouteMatcherTest {
    private val matcher = RouteMatcher()
    private val start = GeoPoint(21.0285, 105.8542)

    /** [count] legs of 20 m each in one direction: real routes have many short segments between graph nodes. */
    private fun pieces(
        count: Int,
        north: Double = 0.0,
        east: Double = 0.0,
    ) = List(count) { north / count to east / count }

    /** Matches every point in order, carrying the state, and returns each match. */
    private fun MeasuredPolyline.matchAll(inputs: List<MatchInput>): List<RouteMatch> {
        var state: MatchState? = null
        return inputs.map { input -> matcher.match(this, state, input).also { state = it.state } }
    }

    private fun at(
        point: GeoPoint,
        speedMps: Double? = null,
        bearingDeg: Double? = null,
    ) = MatchInput(point, speedMps, bearingDeg)

    @Test
    fun `TC-7-MAT-01 a drive along the route counts the distance left down to zero`() {
        val route = polylineFrom(start, *(pieces(10, east = 200.0) + pieces(10, north = 300.0) + pieces(5, east = -100.0)).toTypedArray())
        val fixes = track(route, speedMps = 10.0, noiseM = 4.0)

        val matches = route.matchAll(fixes.map { at(it.point, it.speedMps, it.bearingDeg) })

        matches.map { it.remainingM }.shouldBeSortedDescending()
        matches.last().remainingM shouldBeLessThan 15.0
        matches.zip(fixes).forEach { (match, fix) -> match.alongM shouldBe (fix.alongM plusOrMinus 25.0) }
    }

    @Test
    fun `TC-7-MAT-02 on a loop the first pass matches the early segment, not the later one`() {
        // Around a block and back through the start, then 400 m on along the first street.
        val loop =
            pieces(10, east = 200.0) + pieces(10, north = 200.0) + pieces(10, east = -200.0) + pieces(10, north = -200.0) +
                pieces(20, east = 400.0)
        val route = polylineFrom(start, *loop.toTypedArray())

        val firstPass = route.matchAll((1..9).map { at(start.offset(eastM = it * 20.0 + 3.0)) })

        firstPass.forEach { it.state.cursor shouldBeLessThan 10 }
        firstPass.last().alongM shouldBe (183.0 plusOrMinus 1.0)
    }

    @Test
    fun `TC-7-MAT-02 after the loop the second pass matches the later segment`() {
        val loop =
            pieces(10, east = 200.0) + pieces(10, north = 200.0) + pieces(10, east = -200.0) + pieces(10, north = -200.0) +
                pieces(20, east = 400.0)
        val route = polylineFrom(start, *loop.toTypedArray())
        val drive = track(route, speedMps = 10.0)

        val matches = route.matchAll(drive.map { at(it.point, it.speedMps, it.bearingDeg) })

        matches.last().remainingM shouldBe (0.0 plusOrMinus 1.0)
        matches.map { it.remainingM }.shouldBeSortedDescending()
    }

    @Test
    fun `TC-7-MAT-03 jitter back and forth along the road never makes the distance left grow`() {
        val route = polylineFrom(start, *pieces(25, east = 500.0).toTypedArray())
        val along = listOf(100.0, 110.0, 100.0, 120.0, 110.0, 130.0, 121.0, 140.0, 131.0)

        val matches = route.matchAll(along.map { at(route.pointAt(it)) })

        matches.map { it.remainingM }.shouldBeSortedDescending()
        matches.last().alongM shouldBe (140.0 plusOrMinus 0.5)
    }

    @Test
    fun `TC-7-MAT-04 a real U-turn of more than 20 m back makes the distance left grow`() {
        val route = polylineFrom(start, *pieces(25, east = 500.0).toTypedArray())

        val matches = route.matchAll(listOf(300.0, 250.0).map { at(route.pointAt(it)) })

        matches[1].remainingM shouldBeGreaterThan matches[0].remainingM
        matches[1].alongM shouldBe (250.0 plusOrMinus 0.5)
    }

    @Test
    fun `TC-7-MAT-05 on a two-way road mapped as two lines, the heading picks the driver's side`() {
        // East 300 m, a 25 m U-turn to the north, then west 300 m back: the two sides of one wide road.
        val route = polylineFrom(start, *(pieces(15, east = 300.0) + pieces(1, north = 25.0) + pieces(15, east = -300.0)).toTypedArray())
        val eastbound = route.matchAll(listOf(at(start.offset(eastM = 60.0))))
        // 20 m north of the eastbound line, so only 5 m from the westbound one, but heading east at 10 m/s.
        val driver = at(start.offset(northM = 20.0, eastM = 100.0), speedMps = 10.0, bearingDeg = 90.0)

        val match = matcher.match(route, eastbound.last().state, driver)

        match.state.cursor shouldBeLessThan 15
        match.alongM shouldBe (100.0 plusOrMinus 0.5)
    }

    @Test
    fun `without a heading the same fix matches the nearer line`() {
        val route = polylineFrom(start, *(pieces(15, east = 300.0) + pieces(1, north = 25.0) + pieces(15, east = -300.0)).toTypedArray())
        val eastbound = route.matchAll(listOf(at(start.offset(eastM = 60.0))))

        val match = matcher.match(route, eastbound.last().state, at(start.offset(northM = 20.0, eastM = 100.0)))

        match.state.cursor shouldBe 25
    }

    @Test
    fun `after a gap longer than the window the driver is found again further ahead`() {
        val route = polylineFrom(start, *pieces(100, east = 2_000.0).toTypedArray())
        val before = route.matchAll(listOf(at(route.pointAt(100.0))))

        val after = matcher.match(route, before.last().state, at(route.pointAt(1_500.0)))

        after.alongM shouldBe (1_500.0 plusOrMinus 0.5)
    }
}
