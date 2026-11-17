package veeci.practicing.rts.platform.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.config.RedisConfig
import veeci.practicing.rts.platform.observability.HealthIndicator
import veeci.practicing.rts.platform.observability.HealthStatus
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The instance's Redis connections (phase 10): one for commands and one for pub/sub, as Redis requires (a
 * connection that subscribes can do nothing else). Lettuce reconnects both by itself and subscribes again to
 * every channel after a reconnect. Messages published while the connection is down are lost: live delivery is
 * best effort, and the database stays the source of truth.
 */
class RedisConnections(
    val config: RedisConfig,
    private val metrics: MeterRegistry? = null,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(RedisConnections::class.java)
    private val client = RedisClient.create(checkNotNull(config.url) { "redis.url is required" })
    private val commandConnection: StatefulRedisConnection<String, String> = client.connect()
    private val pubSubConnection: StatefulRedisPubSubConnection<String, String> = client.connectPubSub()
    private val handlers = ConcurrentHashMap<String, (String) -> Unit>()

    /** Non-blocking commands: each returns a future, which callers await with a timeout. */
    val commands: RedisAsyncCommands<String, String> = commandConnection.async()

    init {
        pubSubConnection.addListener(
            object : RedisPubSubAdapter<String, String>() {
                @Suppress("TooGenericExceptionCaught") // one bad message must not stop the others
                override fun message(
                    channel: String,
                    message: String,
                ) {
                    try {
                        handlers[channel]?.invoke(message)
                    } catch (e: Exception) {
                        log.warn("A message on {} could not be handled", channel, e)
                    }
                }
            },
        )
    }

    /**
     * Starts delivering [channel]'s messages to [handler], on Lettuce's event loop: the handler must not block. The
     * returned stage completes when Redis confirmed the subscription.
     */
    fun subscribe(
        channel: String,
        handler: (String) -> Unit,
    ): CompletionStage<Void> {
        handlers[channel] = handler
        return pubSubConnection.async().subscribe(channel)
    }

    /** The returned stage completes when Redis confirmed it. */
    fun unsubscribe(channel: String): CompletionStage<Void> {
        handlers.remove(channel)
        return pubSubConnection.async().unsubscribe(channel)
    }

    /**
     * Counts a publish that did not reach Redis, as `rts_redis_publish_failed_total{channel}`: without it, guests
     * miss live updates and nothing shows an error anywhere.
     */
    fun publishFailed(channel: String) {
        metrics?.counter("rts.redis.publish.failed", "channel", channel)?.increment()
    }

    /** True when Redis answers PING within [timeoutMs]. Blocks, so callers run it on the IO pool. */
    fun ping(timeoutMs: Long = PING_TIMEOUT_MS): Boolean =
        runCatching { commands.ping().get(timeoutMs, TimeUnit.MILLISECONDS) == "PONG" }.getOrDefault(false)

    override fun close() {
        pubSubConnection.close()
        commandConnection.close()
        client.shutdown()
    }

    private companion object {
        const val PING_TIMEOUT_MS = 1_000L
    }
}

/** Reported in `/health/ready`, never critical: without Redis, guests see no live updates, but fixes are stored. */
class RedisHealthIndicator(
    private val redis: RedisConnections,
) : HealthIndicator {
    override val name = "redis"
    override val critical = false

    override fun check(): HealthStatus = if (redis.ping()) HealthStatus.UP else HealthStatus.DOWN
}
