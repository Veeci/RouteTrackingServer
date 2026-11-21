package veeci.practicing.rts.trip.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.testing.trip.InMemoryProgressStateStore
import veeci.practicing.rts.testing.trip.InMemoryRuleStateStore
import veeci.practicing.rts.testing.trip.InMemoryTripEventOutbox
import veeci.practicing.rts.testing.trip.InMemoryTripMomentRepository
import veeci.practicing.rts.testing.trip.InMemoryTripRepository
import veeci.practicing.rts.testing.trip.InMemoryTripRouteRepository
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.tracking.application.api.DriverPresence
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.trip.application.port.out.DriverPosition
import veeci.practicing.rts.trip.application.port.out.ProgressPublisher
import veeci.practicing.rts.trip.application.port.out.ProgressTarget
import veeci.practicing.rts.trip.application.port.out.TripProgress
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant

/** One active trip read per FixesAccepted, shared by arrival and progress, in that order (fleet lag fix A1). */
class DriverMovementServiceTest {
    private val stored = InMemoryTripRepository()
    private var lookups = 0
    private val trips =
        object : TripRepository by stored {
            override suspend fun findActiveForDriver(driver: UserId) = stored.findActiveForDriver(driver).also { lookups++ }
        }
    private val published = mutableListOf<TripProgress>()
    private val publisher =
        object : ProgressPublisher {
            override fun publish(
                position: DriverPosition,
                progress: TripProgress,
            ) {
                published += progress
            }
        }
    private val rerouteRequests = mutableListOf<RerouteRequest>()
    private val noPresence =
        object : DriverPresence {
            override suspend fun lastSeen(drivers: Collection<UserId>) = emptyMap<UserId, kotlin.time.Instant>()
        }
    private val clock = Clock.fixed((T0 + 600.seconds).toJavaInstant(), ZoneOffset.UTC)
    private val arrival =
        TripEventService(
            trips,
            InMemoryRuleStateStore(),
            InMemoryTripMomentRepository(),
            InMemoryTripEventOutbox(),
            noPresence,
            DirectTransactionRunner,
            clock,
        )
    private val progress =
        TripProgressService(InMemoryTripRouteRepository(), InMemoryProgressStateStore(), publisher, DirectTransactionRunner, {
            rerouteRequests += it
            true
        })
    private val service = DriverMovementService(trips, arrival, progress, DirectTransactionRunner)
    private val driver = TripSamples.driver()

    /** A fix [m] meters south of the pickup every 5 s from T0, at 8 m/s. */
    private fun batch(vararg meters: Double) =
        FixesAccepted(
            UUID.randomUUID(),
            "phone-1",
            driver.userId.value,
            1,
            meters.mapIndexed {
                i,
                m,
                ->
                FixesAccepted.Position(GeoPoint(PICKUP.lat - m / 111_195.0, PICKUP.lng), T0 + (i * 5).seconds, 8.0, 0.0, 0.0)
            },
        )

    @Test
    fun `a session without a driver reads no trip`() =
        runTest {
            service.onFixesAccepted(batch(190.0).copy(driverId = null))

            lookups shouldBe 0
            published.shouldBeEmpty()
        }

    @Test
    fun `a driver with no active trip costs one trip read and changes nothing`() =
        runTest {
            service.onFixesAccepted(batch(190.0, 180.0))

            lookups shouldBe 1
            published.shouldBeEmpty()
        }

    @Test
    fun `arrival runs first on the one trip read, and progress follows the stage it left`() =
        runTest {
            val trip = TripSamples.inStatus(TripStatus.ACCEPTED, driver = driver).also { stored.insert(it) }

            service.onFixesAccepted(batch(190.0, 180.0, 20.0, 10.0, 5.0))

            lookups shouldBe 1
            stored.find(trip.id)?.status shouldBe TripStatus.ARRIVED
            // Progress saw ARRIVED: an ACCEPTED trip with no route would have asked for the APPROACH route.
            rerouteRequests.shouldBeEmpty()
            published.single().target shouldBe ProgressTarget.PICKUP
        }

    @Test
    fun `progress alone runs for a trip that is not on the way to the pickup`() =
        runTest {
            TripSamples.inStatus(TripStatus.IN_PROGRESS, driver = driver).also { stored.insert(it) }

            service.onFixesAccepted(batch(190.0, 180.0))

            lookups shouldBe 1
            published.single().target shouldBe ProgressTarget.DESTINATION
        }
}
