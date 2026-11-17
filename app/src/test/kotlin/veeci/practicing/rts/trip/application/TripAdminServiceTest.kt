package veeci.practicing.rts.trip.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.platform.audit.AuditEntry
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.testing.audit.InMemoryAuditLog
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.testing.trip.InMemoryTripEventOutbox
import veeci.practicing.rts.testing.trip.InMemoryTripRepository
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.trip.application.port.out.TripFilter
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripAction
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.CancelledBy
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaInstant

class TripAdminServiceTest {
    private val trips = InMemoryTripRepository()
    private val outbox = InMemoryTripEventOutbox()
    private val audit = InMemoryAuditLog()
    private val now = T0 + 30.minutes
    private val operator = TripSamples.operator()

    private fun service(repository: TripRepository = trips) =
        TripAdminService(repository, outbox, audit, DirectTransactionRunner, Clock.fixed(now.toJavaInstant(), ZoneOffset.UTC))

    private suspend fun stored(status: TripStatus): Trip = TripSamples.inStatus(status).also { trips.insert(it) }

    @Test
    fun `an operator cancels a ride in progress, announced as by OPERATOR and audited with the reason`() =
        runTest {
            val trip = stored(TripStatus.IN_PROGRESS)

            val cancelled = service().cancel(trip.id, "  guest called support  ", operator)

            cancelled.status shouldBe TripStatus.CANCELLED
            cancelled.version shouldBe trip.version + 1
            trips.find(trip.id)!!.status shouldBe TripStatus.CANCELLED
            outbox.events.single().let {
                it.to shouldBe TripStatus.CANCELLED
                it.cancelledBy shouldBe CancelledBy.OPERATOR
                it.at shouldBe now
            }
            audit.entries.single() shouldBe
                AuditEntry(
                    operator,
                    "TRIP_CANCELLED",
                    "TRIP",
                    trip.id.value.toString(),
                    "guest called support",
                    mapOf("from" to "IN_PROGRESS"),
                )
        }

    @Test
    fun `a cancel needs a reason, and only staff may cancel or list`() =
        runTest {
            val trip = stored(TripStatus.ACCEPTED)

            shouldThrow<DomainException> { service().cancel(trip.id, " ", operator) }.code shouldBe TripError.REASON_REQUIRED
            listOf(TripSamples.guest(), TripSamples.driver()).forEach { caller ->
                shouldThrow<DomainException> { service().cancel(trip.id, "why", caller) }.code shouldBe TripError.TRIP_ACTION_FORBIDDEN
                shouldThrow<DomainException> { service().list(caller, TripFilter(), null, 10) }.code shouldBe
                    TripError.TRIP_ACTION_FORBIDDEN
            }
            trips.find(trip.id)!!.status shouldBe TripStatus.ACCEPTED
            audit.entries.shouldBeEmpty()
        }

    @Test
    fun `an ended trip is an illegal transition, an unknown one is not found, and nothing is audited`() =
        runTest {
            val done = stored(TripStatus.COMPLETED)

            shouldThrow<DomainException> { service().cancel(done.id, "late", operator) }.code shouldBe TripError.ILLEGAL_TRANSITION
            shouldThrow<DomainException> { service().cancel(TripId(UUID.randomUUID()), "x", operator) }.code shouldBe
                TripError.TRIP_NOT_FOUND
            audit.entries.shouldBeEmpty()
            outbox.events.shouldBeEmpty()
        }

    @Test
    fun `a cancel that loses the race is a concurrent modification and writes no audit row`() =
        runTest {
            val trip = stored(TripStatus.ARRIVED)
            val racing =
                object : TripRepository by trips {
                    override suspend fun update(trip: Trip) = false
                }

            shouldThrow<DomainException> { service(racing).cancel(trip.id, "why", operator) }.code shouldBe
                TripError.CONCURRENT_MODIFICATION
            audit.entries.shouldBeEmpty()
        }

    @Test
    fun `the list pages newest first and says where the next page starts`() =
        runTest {
            val all = (0 until 5).map { TripSamples.requested(at = T0 + it.minutes).also { trip -> trips.insert(trip) } }

            val first = service().list(operator, TripFilter(), null, 3)
            val second = service().list(TripSamples.admin(), TripFilter(), first.next, 3)

            first.items.map { it.id } shouldBe all.reversed().take(3).map { it.id.value }
            first.next.shouldNotBeNull()
            second.items.map { it.id } shouldBe all.reversed().drop(3).map { it.id.value }
            second.next.shouldBeNull()
        }

    @Test
    fun `the user cancel route refuses staff, who must give a reason`() =
        runTest {
            val trip = stored(TripStatus.ACCEPTED)
            val users = TripService(trips, outbox, DirectTransactionRunner, Clock.systemUTC(), unusedPlanner, unusedRoutes)

            shouldThrow<DomainException> { users.act(ActOnTrip(trip.id, TripAction.CANCEL), operator) }.code shouldBe
                TripError.TRIP_ACTION_FORBIDDEN
        }

    private val unusedPlanner =
        veeci.practicing.rts.testing.routing.TestRouting
            .planner()
    private val unusedRoutes =
        veeci.practicing.rts.testing.trip
            .InMemoryTripRouteRepository()
}
