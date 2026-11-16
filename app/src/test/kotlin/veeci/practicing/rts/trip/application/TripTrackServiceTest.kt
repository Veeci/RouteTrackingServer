package veeci.practicing.rts.trip.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.geo.offset
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.testing.trip.InMemoryTripMomentRepository
import veeci.practicing.rts.testing.trip.InMemoryTripRepository
import veeci.practicing.rts.testing.trip.InMemoryTripRouteRepository
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.tracking.application.api.DriverTrack
import veeci.practicing.rts.tracking.application.api.TrackCursor
import veeci.practicing.rts.tracking.application.api.TrackPoint
import veeci.practicing.rts.trip.domain.TimelineEntry
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripLeg
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The completed sample moves one stage a minute: approach T0+1m..T0+2m, ride T0+3m..T0+4m. */
class TripTrackServiceTest {
    private val trips = InMemoryTripRepository()
    private val track = FakeDriverTrack()
    private val service =
        TripTrackService(trips, InMemoryTripMomentRepository(), InMemoryTripRouteRepository(), track, DirectTransactionRunner)
    private val guest = TripSamples.guest()
    private val driver = TripSamples.driver()

    private suspend fun stored(status: TripStatus): Trip = TripSamples.inStatus(status, guest, driver).also { trips.insert(it) }

    @Test
    fun `the ride holds only the fixes from started to ended, oldest first`() =
        runTest {
            val trip = stored(TripStatus.COMPLETED)
            listOf(2.5, 3.0, 3.5, 4.0).forEach { track.add(driver.userId, T0 + (it * 60_000).toLong().milliseconds) }

            val result = service.track(trip.id, guest, TripLeg.RIDE)

            result.points.map { it.recordedAt } shouldBe listOf(T0 + 3.minutes, T0 + 3.5.minutes) // the end is exclusive
            (result.timeline.last() as TimelineEntry.StageReached).status shouldBe TripStatus.COMPLETED
        }

    @Test
    fun `a long leg is read page by page, and every point comes back`() =
        runTest {
            val trip = stored(TripStatus.COMPLETED)
            repeat(2_500) { track.add(driver.userId, T0 + 3.minutes + (it * 10).milliseconds) }

            service.track(trip.id, driver, TripLeg.RIDE).points shouldHaveSize 2_500
            track.calls shouldBe 3
        }

    @Test
    fun `TC-8-API-04 simplify drops points and keeps the first and the last`() =
        runTest {
            val trip = stored(TripStatus.COMPLETED)
            repeat(30) { track.add(driver.userId, T0 + 3.minutes + it.minutes / 60, PICKUP.offset(northM = it * 10.0)) }

            val full = service.track(trip.id, guest, TripLeg.RIDE).points
            val simplified = service.track(trip.id, guest, TripLeg.RIDE, simplifyM = 5.0).points

            simplified shouldBe listOf(full.first(), full.last())
            shouldThrow<IllegalArgumentException> { service.track(trip.id, guest, TripLeg.RIDE, simplifyM = 51.0) }
        }

    @Test
    fun `TC-8-API-02 the approach is the driver's and admins', and a guest gets TRIP_ACTION_FORBIDDEN`() =
        runTest {
            val trip = stored(TripStatus.COMPLETED)
            track.add(driver.userId, T0 + 1.5.minutes)

            service.track(trip.id, driver, TripLeg.APPROACH).points shouldHaveSize 1
            service.track(trip.id, TripSamples.admin(), TripLeg.APPROACH).points shouldHaveSize 1
            shouldThrow<DomainException> { service.track(trip.id, guest, TripLeg.APPROACH) }.code shouldBe TripError.TRIP_ACTION_FORBIDDEN
        }

    @Test
    fun `TC-8-API-01 a trip that has not ended gets TRIP_NOT_ENDED`() =
        runTest {
            val trip = stored(TripStatus.IN_PROGRESS)

            shouldThrow<DomainException> { service.track(trip.id, guest, TripLeg.RIDE) }.code shouldBe TripError.TRIP_NOT_ENDED
        }

    @Test
    fun `another guest and another driver get TRIP_NOT_FOUND, as for a trip that does not exist`() =
        runTest {
            val trip = stored(TripStatus.COMPLETED)

            listOf(TripSamples.guest(), TripSamples.driver()).forEach {
                shouldThrow<DomainException> { service.track(trip.id, it, TripLeg.RIDE) }.code shouldBe TripError.TRIP_NOT_FOUND
            }
        }

    @Test
    fun `a trip cancelled before it was accepted has no points, and still its timeline`() =
        runTest {
            val trip = stored(TripStatus.CANCELLED)

            val result = service.track(trip.id, guest, TripLeg.RIDE)

            result.points.shouldBeEmpty()
            result.timeline.map { (it as TimelineEntry.StageReached).status } shouldBe listOf(TripStatus.REQUESTED, TripStatus.CANCELLED)
        }

    /** One session per driver; it pages like the Exposed adapter: [from, to), after the cursor, at most limit. */
    private class FakeDriverTrack : DriverTrack {
        private val points = mutableMapOf<UserId, MutableList<TrackPoint>>()
        private val session = UUID.randomUUID()
        var calls = 0

        fun add(
            driver: UserId,
            at: Instant,
            position: GeoPoint = PICKUP,
        ) {
            val list = points.getOrPut(driver) { mutableListOf() }
            list += TrackPoint(position.lat, position.lng, at, 5.0, null, null, TrackCursor(at, session, list.size.toLong(), 0))
        }

        override suspend fun fixes(
            driver: UserId,
            from: Instant,
            to: Instant,
            after: TrackCursor?,
            limit: Int,
        ): List<TrackPoint> {
            calls++
            return points[driver]
                .orEmpty()
                .filter { it.recordedAt >= from && it.recordedAt < to }
                .filter { after == null || it.cursor.seq > after.seq }
                .take(limit)
        }

        override suspend fun rejections(
            driver: UserId,
            from: Instant,
            to: Instant,
        ): Map<String, Int> = emptyMap()
    }
}
