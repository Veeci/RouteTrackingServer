package veeci.practicing.rts.platform.events

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.platform.db.TransactionRunner
import kotlin.time.Duration.Companion.seconds

class OutboxMetricsTest {
    /** The first read throws CancellationException, as a cancelled query does; later reads return a fixed backlog. */
    private class CancelledOnceReads : TransactionRunner {
        var reads = 0

        @Suppress("UNCHECKED_CAST") // the only block OutboxMetrics runs reads (count, age)
        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            reads++
            if (reads == 1) throw CancellationException("query cancelled")
            return (5L to 42L) as T
        }
    }

    @Test
    fun `a read that throws CancellationException while the loop runs is logged and the next read still happens`() =
        runTest {
            val reads = CancelledOnceReads()
            val registry = SimpleMeterRegistry()
            OutboxMetrics(reads, registry, every = 15.seconds).start(backgroundScope)

            runCurrent()
            advanceTimeBy(16.seconds)

            reads.reads shouldBe 2
            registry.get("rts.outbox.pending").gauge().value() shouldBe 5.0
            registry.get("rts.outbox.oldest.age.seconds").gauge().value() shouldBe 42.0
        }
}
