package veeci.practicing.rts.shared.geo

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.math.PI

class HaversineTest {
    private val operaHouse = GeoPoint(10.7766, 106.7031)
    private val benThanhMarket = GeoPoint(10.7725, 106.6980)

    /** Any valid position on Earth, including the edge values ±90 and ±180. NaN would make GeoPoint throw. */
    private val anyPoint =
        Arb.bind(
            Arb.double(-90.0..90.0, includeNaNs = false),
            Arb.double(-180.0..180.0, includeNaNs = false),
        ) { lat, lng -> GeoPoint(lat, lng) }

    @Test
    fun `TC-2-GEO-01 Saigon Opera House to Ben Thanh Market is within 1 percent of the WGS 84 distance`() {
        val wgs84 = 718.89 // Vincenty on the WGS 84 ellipsoid, computed outside this code

        Haversine.distance(operaHouse, benThanhMarket).value shouldBe (wgs84 plusOrMinus wgs84 * 0.01)
    }

    @Test
    fun `one degree of latitude is the Earth radius times pi over 180`() {
        val oneDegree = Haversine.distance(GeoPoint(10.0, 106.0), GeoPoint(11.0, 106.0))

        oneDegree.value shouldBe (Haversine.EARTH_RADIUS_M * PI / 180 plusOrMinus 1e-6)
    }

    @Test
    fun `a short hop across the antimeridian is short, not a trip around the world`() {
        val hop = Haversine.distance(GeoPoint(0.0, 179.99), GeoPoint(0.0, -179.99))

        hop.value shouldBe (2_223.90 plusOrMinus 0.01)
    }

    @Test
    fun `TC-2-GEO-02 distance is symmetric and zero from a point to itself`() =
        runTest {
            checkAll(anyPoint, anyPoint) { a, b ->
                Haversine.distance(a, b) shouldBe Haversine.distance(b, a)
                Haversine.distance(a, a) shouldBe Meters(0.0)
            }
        }

    @Test
    fun `TC-2-GEO-03 a detour through a third point is never shorter`() =
        runTest {
            checkAll(anyPoint, anyPoint, anyPoint) { a, b, c ->
                val direct = Haversine.distance(a, c)
                val detour = Haversine.distance(a, b) + Haversine.distance(b, c)

                // 1 mm of slack for floating-point rounding.
                direct.value shouldBeLessThanOrEqual detour.value + 0.001
            }
        }
}
