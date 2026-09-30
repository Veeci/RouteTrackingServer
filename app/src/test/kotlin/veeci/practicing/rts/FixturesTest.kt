package veeci.practicing.rts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import veeci.practicing.rts.testing.Fixtures

class FixturesTest {
    @Test
    fun `loads a file from testFixtures resources`() {
        Fixtures.text("routes/README.txt") shouldContain "GPX"
    }

    @Test
    fun `missing fixture fails with its path in the message`() {
        shouldThrow<IllegalArgumentException> { Fixtures.text("routes/nope.gpx") }.message shouldContain "routes/nope.gpx"
    }
}
