package veeci.practicing.rts.trip.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.trip.domain.TimelineEntry.MomentRaised
import veeci.practicing.rts.trip.domain.TimelineEntry.RoutePlanned
import veeci.practicing.rts.trip.domain.TimelineEntry.StageReached
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripStatus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The timeline merge and the leg windows (phase 8c). The completed sample moves one stage a minute from T0. */
class TripTimelineTest {
    private val trip = TripSamples.inStatus(TripStatus.COMPLETED)
    private val id = trip.id.value
    private val guest = trip.guestId.value

    private fun route(
        kind: RouteKind,
        version: Int,
        at: kotlin.time.Instant,
        reason: RouteReason = RouteReason.INITIAL,
    ) = TripRoute(trip.id, kind, version, "_p~iF~ps|U", 100.0, 10.0, at, reason)

    @Test
    fun `TC-8-API-03 stages, moments and route versions come back in time order`() {
        val arriving = TripMoment.DriverArriving(id, guest, TripStatus.ACCEPTED, T0 + 90.seconds, remainingM = 150.0)
        val offRoute = TripMoment.OffRoute(id, guest, TripStatus.IN_PROGRESS, T0 + 200.seconds)
        val approach = route(RouteKind.APPROACH, 1, T0 + 70.seconds)
        val trip1 = route(RouteKind.TRIP, 1, T0 + 1.seconds)
        val trip2 = route(RouteKind.TRIP, 2, T0 + 201.seconds, RouteReason.OFF_ROUTE)

        TripTimeline.of(trip, listOf(arriving, offRoute), listOf(trip1, approach, trip2)) shouldContainExactly
            listOf(
                StageReached(T0, TripStatus.REQUESTED),
                RoutePlanned(trip1),
                StageReached(T0 + 1.minutes, TripStatus.ACCEPTED),
                RoutePlanned(approach),
                MomentRaised(arriving),
                StageReached(T0 + 2.minutes, TripStatus.ARRIVED),
                StageReached(T0 + 3.minutes, TripStatus.IN_PROGRESS),
                MomentRaised(offRoute),
                RoutePlanned(trip2),
                StageReached(T0 + 4.minutes, TripStatus.COMPLETED),
            )
    }

    @Test
    fun `at the same time a stage comes before a moment, and a moment before a route`() {
        val at = T0 + 3.minutes // when the ride started
        val offRoute = TripMoment.OffRoute(id, guest, TripStatus.IN_PROGRESS, at)
        val replanned = route(RouteKind.TRIP, 2, at, RouteReason.OFF_ROUTE)

        TripTimeline.of(trip, listOf(offRoute), listOf(replanned)).filter { it.at == at } shouldContainExactly
            listOf(StageReached(at, TripStatus.IN_PROGRESS), MomentRaised(offRoute), RoutePlanned(replanned))
    }

    @Test
    fun `a cancelled trip ends with CANCELLED, and a live trip has no end`() {
        TripTimeline.of(TripSamples.inStatus(TripStatus.CANCELLED), emptyList(), emptyList()).map { (it as StageReached).status } shouldBe
            listOf(TripStatus.REQUESTED, TripStatus.CANCELLED)
        TripTimeline.of(TripSamples.inStatus(TripStatus.ARRIVED), emptyList(), emptyList()).map { (it as StageReached).status } shouldBe
            listOf(TripStatus.REQUESTED, TripStatus.ACCEPTED, TripStatus.ARRIVED)
    }

    @Test
    fun `the ride window is started to ended, and the approach window accepted to arrived`() {
        trip.hasExactRide shouldBe true
        trip.window(TripLeg.RIDE) shouldBe TimeWindow(T0 + 3.minutes, T0 + 4.minutes)
        trip.window(TripLeg.APPROACH) shouldBe TimeWindow(T0 + 1.minutes, T0 + 2.minutes)
    }

    @Test
    fun `a trip without stage times rides from created to updated and has no approach`() {
        val legacy =
            Trip.restore(trip.id, trip.guestId, trip.driverId, trip.pickup, trip.destination, trip.status, T0, T0 + 9.minutes, trip.version)

        legacy.hasExactRide shouldBe false
        legacy.rideWindow() shouldBe TimeWindow(T0, T0 + 9.minutes)
        legacy.approachWindow().shouldBeNull()
    }

    @Test
    fun `a trip cancelled before the ride has no ride window, with or without stage times`() {
        val cancelled = TripSamples.inStatus(TripStatus.CANCELLED)
        val legacy = Trip.restore(trip.id, trip.guestId, null, trip.pickup, trip.destination, TripStatus.CANCELLED, T0, T0 + 9.minutes, 1)

        cancelled.rideWindow().shouldBeNull()
        legacy.rideWindow().shouldBeNull()
    }
}
