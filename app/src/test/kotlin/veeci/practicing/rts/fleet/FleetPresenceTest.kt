package veeci.practicing.rts.fleet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import veeci.practicing.rts.fleet.application.FleetPresence
import veeci.practicing.rts.fleet.application.port.out.FleetPresenceRepository
import veeci.practicing.rts.fleet.application.port.out.HeardLeft
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.UserId
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** The fleet's server-clock presence (fleet lag fix C): what ingest tells it, and what each flush writes. */
class FleetPresenceTest {
    private val t0 = Instant.parse("2026-11-21T08:00:00Z")
    private val flushAt = t0 + 5.seconds
    private val repository = RecordingPresence()
    private val transactions = CountingTransactions()
    private val presence = FleetPresence(repository, transactions, Clock.fixed(flushAt.toJavaInstant(), ZoneOffset.UTC), 5.seconds)
    private val anna = UUID.randomUUID()
    private val binh = UUID.randomUUID()
    private val chi = UUID.randomUUID()

    @Test
    fun `fifty batches of one driver between two flushes are one write with the newest time`() =
        runBlocking {
            repeat(50) { i -> presence.heard(UserId(anna), t0 + (i * 100).milliseconds) }
            // A late call with an older time does not move the time back.
            presence.heard(UserId(anna), t0)

            presence.flush()

            repository.saved shouldContainExactly listOf(mapOf(anna to t0 + 4.9.seconds))
            repository.updatedAt shouldBe flushAt
            transactions.count shouldBe 1
            presence.pending shouldBe 0
        }

    @Test
    fun `nothing heard writes nothing and opens no transaction`() =
        runBlocking {
            presence.flush()

            repository.saved.shouldBeEmpty()
            transactions.count shouldBe 0
        }

    @Test
    fun `a busy row waits for the next flush, and a driver with no row gets one in a transaction of its own`() =
        runBlocking {
            repository.left = HeardLeft(busy = mapOf(binh to t0), missing = mapOf(chi to t0))
            listOf(anna, binh, chi).forEach { presence.heard(UserId(it), t0) }

            presence.flush()

            repository.created shouldContainExactly listOf(chi to t0)
            transactions.count shouldBe 2
            presence.pending shouldBe 1

            repository.left = HeardLeft()
            presence.flush()
            repository.saved.last() shouldBe mapOf(binh to t0)
            presence.pending shouldBe 0
        }

    @Test
    fun `a failed write keeps every time for the next flush, and a newer time heard meanwhile wins`() =
        runBlocking {
            presence.heard(UserId(anna), t0)
            presence.heard(UserId(binh), t0)
            repository.failNext = true

            shouldThrow<IllegalStateException> { presence.flush() }
            presence.heard(UserId(anna), t0 + 3.seconds)
            presence.flush()

            repository.saved.last() shouldBe mapOf(anna to t0 + 3.seconds, binh to t0)
        }

    @Test
    fun `stopping writes what is left`() =
        runBlocking {
            presence.heard(UserId(anna), t0)

            presence.stop()

            repository.saved shouldContainExactly listOf(mapOf(anna to t0))
            repository.left.busy.shouldBeEmpty()
        }

    private class RecordingPresence : FleetPresenceRepository {
        val saved = mutableListOf<Map<UUID, Instant>>()
        val created = mutableListOf<Pair<UUID, Instant>>()
        var updatedAt: Instant? = null
        var left = HeardLeft()
        var failNext = false

        override suspend fun saveHeard(
            heard: Map<UUID, Instant>,
            updatedAt: Instant,
        ): HeardLeft {
            if (failNext) {
                failNext = false
                error("database down")
            }
            saved += heard
            this.updatedAt = updatedAt
            return left
        }

        override suspend fun create(
            driverId: UUID,
            heardAt: Instant,
            updatedAt: Instant,
        ) {
            created += driverId to heardAt
        }
    }

    private class CountingTransactions : TransactionRunner {
        var count = 0

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            count++
            return block()
        }
    }
}
