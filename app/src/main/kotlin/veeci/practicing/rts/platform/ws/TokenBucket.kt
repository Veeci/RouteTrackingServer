package veeci.practicing.rts.platform.ws

/**
 * Allows bursts of up to [capacity] events and refills continuously at [refillPerSecond]. Not thread-safe:
 * each WebSocket session owns one and reads its frames one at a time.
 */
class TokenBucket(
    private val capacity: Int,
    private val refillPerSecond: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var tokens = capacity.toDouble()
    private var last = nanoTime()

    fun tryTake(): Boolean {
        val now = nanoTime()
        tokens = minOf(capacity.toDouble(), tokens + (now - last) / NANOS_PER_SECOND * refillPerSecond)
        last = now
        if (tokens < 1) return false
        tokens -= 1
        return true
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
