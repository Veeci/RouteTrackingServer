package veeci.practicing.rts.platform.events

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.config.EventsConfig
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.RELAY_POOL
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayIT {
    private val env = TestDatabase.freshDatabaseEnv()
    private val dataSource = createDataSource(TestConfig.load(env).db).also { Migrations.run(it) }
    private val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    private val config = EventsConfig(relayInterval = 10.milliseconds, relayBatchSize = 100)

    @AfterEach
    fun closePool() = dataSource.close()

    /** Records the `n` of each event it handles. */
    private class Recorder(
        override val eventType: String = TYPE,
        private val failWhen: (Int) -> Boolean = { false },
    ) : OutboxEventHandler {
        val handled = ConcurrentLinkedQueue<Int>()

        override suspend fun handle(payload: JsonElement) {
            val n =
                payload.jsonObject
                    .getValue("n")
                    .jsonPrimitive.int
            handled += n
            if (failWhen(n)) error("handler failed for $n")
        }
    }

    private suspend fun append(
        vararg ns: Int,
        type: String = TYPE,
    ) = transactions.inTransaction { ns.forEach { n -> OutboxWriter().append(type, buildJsonObject { put("n", n) }) } }

    private fun unpublished(): Long = countOf("SELECT count(*) FROM outbox WHERE published_at IS NULL")

    private fun written(): Long = countOf("SELECT count(*) FROM outbox WHERE event_type = '$WRITTEN'")

    private fun countOf(sql: String): Long =
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.executeQuery(sql).use { r ->
                    r.next()
                    r.getLong(1)
                }
            }
        }

    @Test
    fun `TC-2-OUT-02 each event is delivered once, oldest first, and marked as published`() =
        runTest {
            val recorder = Recorder()
            val relay = OutboxRelay(transactions, listOf(recorder), config)
            append(1, 2, 3)

            relay.deliverPending() shouldBe 3
            relay.deliverPending() shouldBe 0

            recorder.handled.toList() shouldBe listOf(1, 2, 3)
            unpublished() shouldBe 0
        }

    @Test
    fun `TC-2-OUT-02 a failure after handling rolls back, so the event is delivered again`() =
        runTest {
            var failed = false
            val recorder = Recorder(failWhen = { !failed.also { failed = true } }) // fails the first time only
            val relay = OutboxRelay(transactions, listOf(recorder), config)
            append(1)

            relay.deliverPending() shouldBe 0
            unpublished() shouldBe 1
            relay.deliverPending() shouldBe 1

            recorder.handled.toList() shouldBe listOf(1, 1) // at least once: consumers must handle repeats
            unpublished() shouldBe 0
        }

    @Test
    fun `an event that keeps failing is skipped after 5 attempts, and the next one is delivered`() =
        runTest {
            val recorder = Recorder(failWhen = { it == 1 })
            val relay = OutboxRelay(transactions, listOf(recorder), config)
            append(1, 2)

            repeat(5) { relay.deliverPending() }
            relay.deliverPending()

            recorder.handled.count { it == 1 } shouldBe 5
            recorder.handled.count { it == 2 } shouldBe 1
            unpublished() shouldBe 0
        }

    @Test
    fun `one pass delivers at most one batch`() =
        runTest {
            val recorder = Recorder()
            val relay = OutboxRelay(transactions, listOf(recorder), config.copy(relayBatchSize = 2))
            append(1, 2, 3, 4, 5)

            relay.deliverPending() shouldBe 2
            relay.deliverPending() shouldBe 2
            relay.deliverPending() shouldBe 1

            recorder.handled.toList() shouldBe listOf(1, 2, 3, 4, 5)
            unpublished() shouldBe 0
        }

    @Test
    fun `a failing event rolls back its pass, and the events before it are delivered again without it`() =
        runTest {
            var failed = false
            val recorder = Recorder(failWhen = { it == 2 && !failed.also { failed = true } }) // 2 fails once
            val relay = OutboxRelay(transactions, listOf(recorder), config)
            append(1, 2, 3)

            relay.deliverPending() shouldBe 1 // the pass 1, 2 (fails) rolls back; then 1 alone
            unpublished() shouldBe 2
            relay.deliverPending() shouldBe 2

            recorder.handled.toList() shouldBe listOf(1, 2, 1, 2, 3)
            unpublished() shouldBe 0
        }

    @Test
    fun `on the relay's own pool, a handler's writes through the main runner roll back with the pass`() =
        runTest {
            createDataSource(TestConfig.load(env).db, name = RELAY_POOL, size = 2).use { relayPool ->
                val relayTransactions = ExposedTransactionRunner(Database.connect(relayPool), maxConnections = 2)
                val writesThenFails =
                    object : OutboxEventHandler {
                        override val eventType = TYPE

                        override suspend fun handle(payload: JsonElement) {
                            transactions.inTransaction { OutboxWriter().append(WRITTEN, buildJsonObject { put("n", 0) }) }
                            error("the handler fails after its write")
                        }
                    }
                append(1)

                OutboxRelay(relayTransactions, listOf(writesThenFails), config).deliverPending() shouldBe 0
            }

            written() shouldBe 0
            unpublished() shouldBe 1
        }

    @Test
    fun `an event without a handler is marked as published`() =
        runTest {
            val relay = OutboxRelay(transactions, listOf(Recorder()), config)
            append(1, type = "nobody.Listens.v1")

            relay.deliverPending() shouldBe 1

            unpublished() shouldBe 0
        }

    @Test
    fun `two relays at once, like two server instances, deliver each event exactly once`() =
        runTest {
            val counts = ConcurrentHashMap<Int, Int>()
            val handler =
                object : OutboxEventHandler {
                    override val eventType = TYPE

                    override suspend fun handle(payload: JsonElement) {
                        counts.merge((payload.jsonObject.getValue("n") as JsonPrimitive).int, 1, Int::plus)
                    }
                }
            append(*(1..200).toList().toIntArray())
            val relays = List(2) { OutboxRelay(transactions, listOf(handler), config.copy(relayBatchSize = 500)) }

            val delivered = coroutineScope { relays.map { async(Dispatchers.IO) { it.deliverUntilEmpty() } }.awaitAll() }

            delivered.sum() shouldBe 200
            counts.size shouldBe 200
            counts.values.all { it == 1 } shouldBe true
        }

    @Test
    fun `TC-10-REL-01 two relays never handle two events at once, so the events come in order`() =
        runTest {
            val order = ConcurrentLinkedQueue<Int>()
            val inFlight = AtomicInteger()
            val mostAtOnce = AtomicInteger()
            val handler =
                object : OutboxEventHandler {
                    override val eventType = TYPE

                    override suspend fun handle(payload: JsonElement) {
                        mostAtOnce.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                        delay(1) // long enough for the other relay to try
                        order += (payload.jsonObject.getValue("n") as JsonPrimitive).int
                        inFlight.decrementAndGet()
                    }
                }
            append(*(1..100).toList().toIntArray())
            val relays = List(2) { OutboxRelay(transactions, listOf(handler), config) }

            val delivered = coroutineScope { relays.map { async(Dispatchers.IO) { it.deliverUntilEmpty() } }.awaitAll() }

            delivered.sum() shouldBe 100
            order.toList() shouldBe (1..100).toList()
            mostAtOnce.get() shouldBe 1
        }

    @Test
    fun `TC-10-OBS-01 the backlog gauges show what waits, and the relay counts what it delivered`() =
        runTest {
            val metrics = SimpleMeterRegistry()
            val backlog = OutboxMetrics(transactions, metrics)
            append(1, 2, 3)

            backlog.refresh()
            metrics.get("rts.outbox.pending").gauge().value() shouldBe 3.0
            (metrics.get("rts.outbox.oldest.age.seconds").gauge().value() >= 0.0) shouldBe true

            OutboxRelay(transactions, listOf(Recorder()), config, metrics).deliverPending() shouldBe 3
            backlog.refresh()
            metrics.get("rts.outbox.pending").gauge().value() shouldBe 0.0
            metrics.get("rts.relay.delivered").counter().count() shouldBe 3.0
        }

    /** Like the relay loop: delivers until the outbox is empty, whoever else delivers at the same time. */
    private suspend fun OutboxRelay.deliverUntilEmpty(): Int {
        var delivered = 0
        while (unpublished() > 0) delivered += deliverPending()
        return delivered
    }

    @Test
    fun `a handler that throws CancellationException fails its event but never stops the running relay`() =
        runBlocking<Unit> {
            // A driver socket that closes while the relay sends it a command throws CancellationException from send().
            // The relay's own coroutine is still active, so this is a failed delivery, not a reason to stop.
            var thrown = false
            val closingSocket =
                object : OutboxEventHandler {
                    override val eventType = TYPE

                    override suspend fun handle(payload: JsonElement) {
                        if (!thrown) {
                            thrown = true
                            throw CancellationException("socket closed")
                        }
                    }
                }
            val recorder = Recorder()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val relay = OutboxRelay(transactions, listOf(closingSocket, recorder), config)
                relay.start(scope)
                append(1, 2)

                withTimeout(5.seconds) { while (unpublished() > 0) delay(20) }

                relay.isRunning shouldBe true
                recorder.handled.toList() shouldBe listOf(1, 2)
            } finally {
                scope.cancel()
            }
        }

    private companion object {
        const val TYPE = "test.Numbered.v1"
        const val WRITTEN = "test.Written.v1"
    }
}
