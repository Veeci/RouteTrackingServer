package veeci.practicing.rts.shared.geo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PolylineCodecTest {
    private val reference = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))

    // From Google's Encoded Polyline documentation. It contains a pipe and a backtick, hence the raw string.
    private val referenceEncoded = """_p~iF~ps|U_ulLnnqC_mqNvxq`@"""

    @Test
    fun `TC-6-PL-01 Google's reference points encode to Google's reference string`() {
        PolylineCodec.encode(reference) shouldBe referenceEncoded
    }

    @Test
    fun `Google's reference string decodes to the reference points`() {
        PolylineCodec.decode(referenceEncoded) shouldBe reference
    }

    @Test
    fun `TC-6-PL-02 decode of encode gives the points rounded to 1e-5 degrees`() =
        runTest {
            val lats = Arb.double(-90.0, 90.0, includeNaNs = false)
            val lngs = Arb.double(-180.0, 180.0, includeNaNs = false)
            val points = arbitrary { GeoPoint(lats.bind(), lngs.bind()) }
            checkAll(PropTestConfig(seed = 20_261_113L), Arb.list(points, 0..50)) { route ->
                val decoded = PolylineCodec.decode(PolylineCodec.encode(route))

                decoded shouldBe route.map { GeoPoint(Math.round(it.lat * 1e5) / 1e5, Math.round(it.lng * 1e5) / 1e5) }
            }
        }

    @Test
    fun `an empty route encodes to an empty string`() {
        PolylineCodec.encode(emptyList()) shouldBe ""
        PolylineCodec.decode("") shouldBe emptyList()
    }

    @Test
    fun `malformed input is rejected`() {
        shouldThrow<IllegalArgumentException> { PolylineCodec.decode("_p~iF") } // latitude with no longitude
        shouldThrow<IllegalArgumentException> { PolylineCodec.decode("_p~i") } // ends inside a number
        shouldThrow<IllegalArgumentException> { PolylineCodec.decode("_p~iF ~ps|U") } // space is below '?'
        shouldThrow<IllegalArgumentException> { PolylineCodec.decode("~".repeat(20) + "?") } // longer than a Long
    }
}
