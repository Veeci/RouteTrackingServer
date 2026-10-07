package veeci.practicing.rts.platform.events

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
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
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds

@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }
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

    private fun unpublished(): Long =
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.executeQuery("SELECT count(*) FROM outbox WHERE published_at IS NULL").use { r ->
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

            val delivered = coroutineScope { relays.map { async(Dispatchers.IO) { it.deliverPending() } }.awaitAll() }

            delivered.sum() shouldBe 200
            counts.size shouldBe 200
            counts.values.all { it == 1 } shouldBe true
        }

    private companion object {
        const val TYPE = "test.Numbered.v1"
    }
}
