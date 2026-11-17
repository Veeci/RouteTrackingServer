package veeci.practicing.rts.tracking

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.config.RedisConfig
import veeci.practicing.rts.platform.redis.RedisConnections
import veeci.practicing.rts.protocol.Command
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.testing.TestRedis
import veeci.practicing.rts.tracking.adapter.out.redis.RedisDriverLink
import veeci.practicing.rts.tracking.application.api.LocalDriverLink
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** TC-10-MI-02, the Redis half: a message for a driver whose socket is on the other instance gets there. */
@Testcontainers(disabledWithoutDocker = true)
class RedisDriverLinkIT {
    private val connections = mutableListOf<RedisConnections>()
    private val driver = UserId(UUID.randomUUID())
    private val command = Command(UUID.randomUUID().toString(), "FLUSH_QUEUE")

    /** One instance's sockets: drivers that are "here", and what each was sent. */
    private class FakeSockets : LocalDriverLink {
        private val here = mutableSetOf<UserId>()
        private val presence = CopyOnWriteArrayList<suspend (UserId, Boolean) -> Unit>()
        val sent = ConcurrentLinkedQueue<Pair<UserId, ServerMessage>>()

        suspend fun connect(driver: UserId) {
            here += driver
            presence.forEach { it(driver, true) }
        }

        suspend fun disconnect(driver: UserId) {
            here -= driver
            presence.forEach { it(driver, false) }
        }

        override suspend fun send(
            driver: UserId,
            message: ServerMessage,
        ): Boolean = (driver in here).also { if (it) sent += driver to message }

        override fun onConnected(listener: suspend (UserId) -> Unit) = Unit

        override fun onCommandAck(listener: suspend (UserId, UUID, Boolean) -> Unit) = Unit

        override fun onPresenceChanged(listener: suspend (UserId, Boolean) -> Unit) {
            presence += listener
        }
    }

    private fun instance(sockets: FakeSockets) =
        RedisDriverLink(
            sockets,
            RedisConnections(RedisConfig(TestRedis.url(), publishTimeout = 300.milliseconds)).also { connections += it },
        )

    @AfterEach
    fun close() = connections.forEach { it.close() }

    @Test
    fun `TC-10-MI-02 a command sent on instance A reaches the driver's socket on B, once`() =
        runBlocking<Unit> {
            val socketsA = FakeSockets()
            val socketsB = FakeSockets()
            val a = instance(socketsA)
            instance(socketsB)
            socketsB.connect(driver)

            a.send(driver, command) shouldBe true

            eventually { socketsB.sent.toList() shouldBe listOf(driver to command) }
            delay(100)
            socketsB.sent.size shouldBe 1
            socketsA.sent.size shouldBe 0
        }

    @Test
    fun `a driver connected nowhere is reported as not reached, so the command waits for the next welcome`() =
        runBlocking<Unit> {
            val socketsB = FakeSockets()
            val a = instance(FakeSockets())
            instance(socketsB)
            socketsB.connect(driver)
            socketsB.disconnect(driver)

            a.send(driver, command) shouldBe false
        }

    @Test
    fun `a driver on the same instance is sent to directly`() =
        runBlocking<Unit> {
            val sockets = FakeSockets()
            val a = instance(sockets)
            sockets.connect(driver)

            a.send(driver, command) shouldBe true
            sockets.sent.toList() shouldBe listOf(driver to command)
        }

    private suspend fun eventually(assertion: suspend () -> Unit) {
        val end = TimeSource.Monotonic.markNow() + 5.seconds
        while (true) {
            try {
                return assertion()
            } catch (e: AssertionError) {
                if (end.hasPassedNow()) throw e
                delay(20)
            }
        }
    }
}
