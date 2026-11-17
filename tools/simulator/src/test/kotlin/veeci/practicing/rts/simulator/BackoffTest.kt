package veeci.practicing.rts.simulator

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test
    fun `the ceiling doubles from 1 s up to 30 s, and every wait is at most the ceiling`() {
        val backoff = Backoff(random = Random(42))

        val ceilings = (1..7).map { backoff.ceiling.also { ceiling -> (backoff.next() <= ceiling) shouldBe true } }

        ceilings shouldBe listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds }
    }

    @Test
    fun `waits spread out instead of lining up, and a reset starts again from 1 s`() {
        val waits = (1..100).map { Backoff(random = Random(it)).apply { repeat(3) { next() } }.next() }

        (waits.toSet().size > 50) shouldBe true // 100 phones retrying after the same failures do not retry together
        Backoff().apply { repeat(5) { next() } }.apply { reset() }.ceiling shouldBe 1.seconds
    }
}
