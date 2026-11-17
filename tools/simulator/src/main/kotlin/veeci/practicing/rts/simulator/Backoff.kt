package veeci.practicing.rts.simulator

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Waits between reconnects, with "full jitter": a random wait between 0 and a ceiling that doubles after every
 * failure, from [base] up to [cap]. Without the randomness, every phone that a deploy disconnected would retry at
 * the same seconds (1, 2, 4, 8 ...) and hit the new instance all together. The SDK should do the same.
 */
class Backoff(
    private val base: Duration = 1.seconds,
    private val cap: Duration = 30.seconds,
    private val random: Random = Random.Default,
) {
    private var failures = 0

    /** The ceiling for the next wait: [base] times 2 to the number of failures, at most [cap]. */
    val ceiling: Duration get() = (base * (1L shl failures.coerceAtMost(MAX_SHIFT)).toDouble()).coerceAtMost(cap)

    /** A random wait up to [ceiling]; the next ceiling doubles. */
    fun next(): Duration = random.nextLong(ceiling.inWholeMilliseconds + 1).milliseconds.also { failures++ }

    /** After a connection that worked: start again from [base]. */
    fun reset() {
        failures = 0
    }

    private companion object {
        const val MAX_SHIFT = 20 // far above any cap; keeps the shift from overflowing
    }
}
