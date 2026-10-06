package veeci.practicing.rts.shared.geo

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class GeoPointTest {
    @Test
    fun `TC-2-GEO-04 a latitude above 90 is refused`() {
        shouldThrow<IllegalArgumentException> { GeoPoint(91.0, 0.0) }.message shouldContain "lat=91.0"
    }

    @Test
    fun `a longitude beyond 180 is refused`() {
        shouldThrow<IllegalArgumentException> { GeoPoint(0.0, 180.5) }
    }

    @Test
    fun `NaN is refused because it is in no range`() {
        shouldThrow<IllegalArgumentException> { GeoPoint(Double.NaN, 0.0) }
        GeoPoint.isValid(0.0, Double.POSITIVE_INFINITY) shouldBe false
    }

    @Test
    fun `the poles and the antimeridian are valid`() {
        shouldNotThrowAny {
            GeoPoint(90.0, 180.0)
            GeoPoint(-90.0, -180.0)
        }
    }
}
