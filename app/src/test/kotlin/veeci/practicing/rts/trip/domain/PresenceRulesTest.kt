package veeci.practicing.rts.trip.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PresenceRulesTest {
    private val rules = PresenceRules(staleAfter = 60.seconds)
    private val t0 = Instant.parse("2026-11-12T08:00:00Z")

    @Test
    fun `TC-4-STL-01 last heard at t0, nothing at t0+59 s, stale at t0+61 s`() {
        rules.evaluate(PresenceState(), t0, t0 + 59.seconds) shouldBe PresenceOutcome(PresenceState())

        val outcome = rules.evaluate(PresenceState(), t0, t0 + 61.seconds)

        outcome.staleSince shouldBe t0
        outcome.state.stale shouldBe true
    }

    @Test
    fun `TC-4-STL-02 still silent at t0+120 s raises no second stale`() {
        val stale = rules.evaluate(PresenceState(), t0, t0 + 61.seconds).state

        rules.evaluate(stale, t0, t0 + 120.seconds) shouldBe PresenceOutcome(stale)
    }

    @Test
    fun `TC-4-STL-03 heard again after stale raises back once`() {
        val stale = rules.evaluate(PresenceState(), t0, t0 + 61.seconds).state

        val back = rules.evaluate(stale, t0 + 70.seconds, t0 + 71.seconds)
        back.back shouldBe true
        back.state.stale shouldBe false

        rules.evaluate(back.state, t0 + 80.seconds, t0 + 81.seconds).back shouldBe false
    }
}
