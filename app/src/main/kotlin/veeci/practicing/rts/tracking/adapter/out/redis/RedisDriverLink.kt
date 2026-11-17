package veeci.practicing.rts.tracking.adapter.out.redis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.redis.RedisConnections
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.ServerMessage
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.DriverLink
import veeci.practicing.rts.tracking.application.api.LocalDriverLink

/**
 * The driver sockets of every instance (phase 10). A message for a driver whose socket is on another instance goes
 * through Redis on `driver:{id}`, and that instance sends it.
 *
 * Each instance subscribes to `driver:{id}` while it holds a socket of that driver, so `PUBLISH` returns how many
 * instances hold one. That count is [send]'s answer: true means some instance got it, as on one instance. With
 * Redis down, [send] answers false and the sender falls back to what it does for an offline driver: commands wait
 * as PENDING and go out after the driver's next `welcome`.
 */
class RedisDriverLink(
    private val local: LocalDriverLink,
    private val redis: RedisConnections,
) : DriverLink by local {
    private val log = LoggerFactory.getLogger(RedisDriverLink::class.java)

    // Lettuce calls message handlers on its event loop, which must not wait: sending to a socket runs here.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        local.onPresenceChanged { driver, here ->
            val channel = channel(driver)
            val change = if (here) redis.subscribe(channel) { deliver(driver, it) } else redis.unsubscribe(channel)
            quietly { change.await() }
        }
    }

    override suspend fun send(
        driver: UserId,
        message: ServerMessage,
    ): Boolean {
        if (local.send(driver, message)) return true
        val text = ProtocolJson.encodeToString(ServerMessage.serializer(), message)
        val instances = quietly { redis.commands.publish(channel(driver), text).await() }
        if (instances == null) redis.publishFailed("driver")
        return (instances ?: 0L) > 0
    }

    private fun deliver(
        driver: UserId,
        text: String,
    ) {
        val message = ProtocolJson.decodeFromString(ServerMessage.serializer(), text)
        scope.launch { local.send(driver, message) }
    }

    @Suppress("TooGenericExceptionCaught") // Redis down: the caller treats the driver as offline
    private suspend fun <T> quietly(block: suspend () -> T): T? =
        try {
            withTimeoutOrNull(redis.config.publishTimeout) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Redis did not answer: {}", e.toString())
            null
        }

    private companion object {
        fun channel(driver: UserId) = "driver:${driver.value}"
    }
}
