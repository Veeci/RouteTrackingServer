package veeci.practicing.rts.shared.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Công thức haversine dùng để tính khoảng cách trên mặt Trái Đất giữa hai điểm, khi biết vĩ độ và kinh độ của chúng
 *  h = sin²(Δφ / 2) + cos φ₁ · cos φ₂ · sin²(Δλ / 2)
 *  d = 2R · arcsin( √h )
 */
object Haversine {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun distance(
        a: GeoPoint,
        b: GeoPoint,
    ): Meters = Meters(distanceM(a.lat, a.lng, b.lat, b.lng))

    /**
     * The same distance from raw degrees, in metres. Search loops call it once per edge; taking doubles
     * instead of [GeoPoint]s means they allocate nothing.
     */
    fun distanceM(
        latA: Double,
        lngA: Double,
        latB: Double,
        lngB: Double,
    ): Double {
        val lat1 = Math.toRadians(latA)
        val lat2 = Math.toRadians(latB)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(lngB - lngA)
        val h = sin(dLat / 2).squared() + cos(lat1) * cos(lat2) * sin(dLng / 2).squared()
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceAtMost(1.0)))
    }

    private fun Double.squared() = this * this
}
