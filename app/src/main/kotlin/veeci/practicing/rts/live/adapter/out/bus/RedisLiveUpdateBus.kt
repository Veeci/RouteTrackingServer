package veeci.practicing.rts.live.adapter.out.bus

import io.lettuce.core.ScriptOutputType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import veeci.practicing.rts.live.application.api.LiveUpdate
import veeci.practicing.rts.live.application.port.out.LiveUpdateBus
import veeci.practicing.rts.platform.redis.RedisConnections
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The live bus across instances (phase 10). A publish goes to Redis, and every instance with a guest of that trip
 * hands it to [local], which fans it out to its own sockets exactly as on one instance.
 *
 * - One Lua script per publish numbers the update (`INCR trip:seq:{id}`), keeps it as the trip's latest
 *   (`trip:last:{id}`) and publishes it on `trip:{id}`, atomically, so the number orders every copy.
 * - An instance subscribes to `trip:{id}` only while it has a guest of that trip, so a trip's updates reach only
 *   the instances that need them, and `PUBSUB NUMSUB` says how many instances watch it.
 * - A late subscriber: subscribe first, then read `trip:last:{id}`. An update published in between arrives
 *   twice, and the number drops the older copy. Reading first would lose it.
 * - Best effort: a publish never waits and never throws. With Redis down, guests see nothing new until it is
 *   back, and the fixes, trips and events in Postgres are unaffected.
 */
class RedisLiveUpdateBus(
    private val redis: RedisConnections,
    private val local: InMemoryLiveUpdateBus = InMemoryLiveUpdateBus(),
) : LiveUpdateBus {
    private val log = LoggerFactory.getLogger(RedisLiveUpdateBus::class.java)
    private val watchers = ConcurrentHashMap<UUID, Int>()
    private val lastSeq = ConcurrentHashMap<UUID, Long>()
    private val lock = Any()

    override val tripCount: Int get() = local.tripCount

    override fun publish(update: LiveUpdate) {
        val trip = update.tripId
        val ttl =
            redis.config.lastUpdateTtl.inWholeMilliseconds
                .toString()
        runCatching {
            redis.commands
                .eval<Long>(
                    PUBLISH_SCRIPT,
                    ScriptOutputType.INTEGER,
                    arrayOf(seqKey(trip), lastKey(trip), channel(trip)),
                    LiveUpdateWire.encode(update),
                    ttl,
                ).toCompletableFuture()
                .orTimeout(redis.config.publishTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .whenComplete { _, error ->
                    if (error !=
                        null
                    ) {
                        log.warn("Live update of trip {} not published: {}", trip, error.toString())
                    }
                }
        }.onFailure { log.warn("Live update of trip {} not published: {}", trip, it.toString()) }
    }

    override fun subscribe(tripId: UUID): Flow<LiveUpdate> =
        flow {
            if (join(tripId)) seedFromLatest(tripId)
            try {
                emitAll(local.subscribe(tripId))
            } finally {
                withContext(NonCancellable) { leave(tripId) }
            }
        }

    override fun subscriberCount(tripId: UUID): Int = local.subscriberCount(tripId)

    override suspend fun isWatched(tripId: UUID): Boolean =
        local.subscriberCount(tripId) > 0 ||
            quietly {
                redis.commands
                    .pubsubNumsub(channel(tripId))
                    .await()
                    .values
                    .sum() > 0
            } ?: false

    /**
     * Counts this instance's watcher. The first one subscribes to the trip's channel and waits until Redis confirms,
     * so that the read of the latest update that follows cannot come before the subscription. True for the first.
     */
    private suspend fun join(tripId: UUID): Boolean {
        val subscribed =
            synchronized(lock) {
                val count = (watchers[tripId] ?: 0) + 1
                watchers[tripId] = count
                if (count == 1) redis.subscribe(channel(tripId)) { receive(tripId, it) } else null
            } ?: return false
        awaitQuietly(subscribed, "subscribe to", tripId)
        return true
    }

    /** The last watcher unsubscribes and waits, so that [isWatched] right after no longer counts this instance. */
    private suspend fun leave(tripId: UUID) {
        val unsubscribed =
            synchronized(lock) {
                val count = (watchers[tripId] ?: 1) - 1
                if (count > 0) {
                    watchers[tripId] = count
                    null
                } else {
                    watchers.remove(tripId)
                    lastSeq.remove(tripId)
                    redis.unsubscribe(channel(tripId))
                }
            } ?: return
        awaitQuietly(unsubscribed, "unsubscribe from", tripId)
    }

    @Suppress("TooGenericExceptionCaught") // Redis down: the guest still gets this instance's updates
    private suspend fun awaitQuietly(
        stage: CompletionStage<Void>,
        what: String,
        tripId: UUID,
    ) {
        try {
            withTimeoutOrNull(redis.config.publishTimeout) { stage.await() } ?: log.warn("Could not {} trip {} in time", what, tripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not {} trip {}: {}", what, tripId, e.toString())
        }
    }

    private suspend fun seedFromLatest(tripId: UUID) {
        val latest = quietly { redis.commands.get(lastKey(tripId)).await() } ?: return
        receive(tripId, latest)
    }

    /** [block] within the publish timeout; null on a timeout or a Redis error, which is logged. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> quietly(block: suspend () -> T): T? =
        try {
            withTimeoutOrNull(redis.config.publishTimeout) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Redis did not answer: {}", e.toString())
            null
        }

    /** `<seq>|<json>`: hands the update to the local fan-out unless a newer one came first. */
    private fun receive(
        tripId: UUID,
        message: String,
    ) {
        val seq = message.substringBefore('|').toLong()
        var newer = false
        lastSeq.compute(tripId) { _, seen ->
            newer = seen == null || seq > seen
            if (newer) seq else seen
        }
        if (newer) local.publish(LiveUpdateWire.decode(message.substringAfter('|')))
    }

    private companion object {
        fun channel(trip: UUID) = "trip:$trip"

        fun lastKey(trip: UUID) = "trip:last:$trip"

        fun seqKey(trip: UUID) = "trip:seq:$trip"

        /** KEYS: seq, last, channel. ARGV: the update as JSON, the time to keep the keys in ms. */
        const val PUBLISH_SCRIPT = """
            local seq = redis.call('INCR', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            local message = seq .. '|' .. ARGV[1]
            redis.call('SET', KEYS[2], message, 'PX', ARGV[2])
            redis.call('PUBLISH', KEYS[3], message)
            return seq
        """
    }
}
