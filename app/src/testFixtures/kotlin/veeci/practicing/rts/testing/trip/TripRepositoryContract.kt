package veeci.practicing.rts.testing.trip

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.trip.application.port.out.TripCursor
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripAction
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What TripService relies on from a trip repository (TC-3-DB-01). The in-memory version (unit tests) and the
 * Postgres version (integration tests) both extend this class and must pass the same tests.
 */
abstract class TripRepositoryContract {
    abstract val trips: TripRepository

    /** The database version runs every call in a transaction; the in-memory version needs none. */
    open val transactions: TransactionRunner = DirectTransactionRunner

    protected suspend fun <T> tx(block: suspend () -> T): T = transactions.inTransaction(block)

    private fun Trip.values() = listOf(id, guestId, driverId, pickup, destination, status, createdAt, updatedAt, version)

    @Test
    fun `a trip reads back exactly as it was inserted`() =
        runTest {
            val trip = TripSamples.requested()

            tx { trips.insert(trip) }

            tx { trips.find(trip.id) }!!.values() shouldBe trip.values()
            tx { trips.find(TripId(UUID.randomUUID())) }.shouldBeNull()
        }

    @Test
    fun `an update with the version that was read saves the change and raises the version`() =
        runTest {
            val trip = TripSamples.requested()
            tx { trips.insert(trip) }
            val loaded = tx { trips.find(trip.id) }!!
            val driver = TripSamples.driver()
            loaded.perform(TripAction.ACCEPT, driver, T0 + 1.minutes)

            tx { trips.update(loaded) } shouldBe true

            val stored = tx { trips.find(trip.id) }!!
            stored.status shouldBe TripStatus.ACCEPTED
            stored.driverId shouldBe driver.userId
            stored.updatedAt shouldBe T0 + 1.minutes
            stored.version shouldBe 2
        }

    @Test
    fun `TC-3-DB-02 an update with an old version changes nothing and says so`() =
        runTest {
            val trip = TripSamples.requested()
            tx { trips.insert(trip) }
            val first = tx { trips.find(trip.id) }!!
            val second = tx { trips.find(trip.id) }!!
            first.perform(TripAction.ACCEPT, TripSamples.driver(), T0 + 1.minutes)
            second.perform(TripAction.CANCEL, TripSamples.guest().copy(userId = trip.guestId), T0 + 2.minutes)
            tx { trips.update(first) } shouldBe true

            tx { trips.update(second) } shouldBe false

            tx { trips.find(trip.id) }!!.status shouldBe TripStatus.ACCEPTED
        }

    @Test
    fun `trips of one status come oldest first, a page at a time`() =
        runTest {
            val open = (0..4).map { TripSamples.requested(at = T0 + it.seconds) }
            val accepted = TripSamples.inStatus(TripStatus.ACCEPTED, at = T0 + 10.seconds)
            tx { (open.reversed() + accepted).forEach { trips.insert(it) } }

            val first = tx { trips.findByStatus(TripStatus.REQUESTED, after = null, limit = 2) }
            val cursor = first.last().let { TripCursor(it.createdAt, it.id) }
            val rest = tx { trips.findByStatus(TripStatus.REQUESTED, after = cursor, limit = 10) }

            (first + rest).map { it.id } shouldBe open.map { it.id }
        }

    @Test
    fun `trips with the same creation time are paged by id`() =
        runTest {
            // The database orders uuids as text (byte by byte), not like java.util.UUID.compareTo.
            val open = (0..3).map { TripSamples.requested(at = T0) }.sortedBy { it.id.value.toString() }
            tx { open.forEach { trips.insert(it) } }

            val first = tx { trips.findByStatus(TripStatus.REQUESTED, after = null, limit = 2) }
            val rest = tx { trips.findByStatus(TripStatus.REQUESTED, TripCursor(T0, first.last().id), limit = 10) }

            (first + rest).map { it.id } shouldBe open.map { it.id }
        }

    @Test
    fun `the active trip of a driver is found until it is finished`() =
        runTest {
            val driver = TripSamples.driver()
            val trip = TripSamples.inStatus(TripStatus.IN_PROGRESS, driver = driver)
            tx { trips.insert(trip) }

            tx { trips.findActiveForDriver(driver.userId) }!!.id shouldBe trip.id

            trip.perform(TripAction.COMPLETE, driver, T0 + 1.minutes)
            tx { trips.update(trip) }
            tx { trips.findActiveForDriver(driver.userId) }.shouldBeNull()
        }

    @Test
    fun `TC-3-DB-04 a second active trip for one driver is refused by the store itself`() =
        runTest {
            val driver = TripSamples.driver()
            tx { trips.insert(TripSamples.inStatus(TripStatus.ACCEPTED, driver = driver)) }
            val other = TripSamples.requested()
            tx { trips.insert(other) }
            other.perform(TripAction.ACCEPT, driver, T0 + 1.minutes) // the service's check is skipped on purpose

            shouldThrow<DomainException> { tx { trips.update(other) } }.code shouldBe TripError.DRIVER_ALREADY_ON_TRIP

            tx { trips.find(other.id) }!!.status shouldBe TripStatus.REQUESTED
        }
}
