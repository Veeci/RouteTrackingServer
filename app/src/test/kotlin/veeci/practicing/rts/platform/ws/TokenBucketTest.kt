package veeci.practicing.rts.platform.ws

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TokenBucketTest {
    private var now = 0L
    private val bucket = TokenBucket(capacity = 3, refillPerSecond = 2, nanoTime = { now })

    @Test
    fun `allows a burst up to capacity, then refuses`() {
        List(4) { bucket.tryTake() } shouldBe listOf(true, true, true, false)
    }

    @Test
    fun `refills at the configured rate`() {
        repeat(3) { bucket.tryTake() }

        now += 500_000_000 // half a second at 2 tokens/s = 1 token

        bucket.tryTake() shouldBe true
        bucket.tryTake() shouldBe false
    }

    @Test
    fun `never stores more than capacity, however long it waits`() {
        now += 60_000_000_000 // a minute

        List(4) { bucket.tryTake() } shouldBe listOf(true, true, true, false)
    }
}
