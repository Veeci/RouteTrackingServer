package veeci.practicing.rts.shared.geo

data class GeoPoint(
    val lat: Double,
    val lng: Double,
) {
    init {
        require(isValid(lat, lng)) { "Not a valid WGS 84 position: lat=$lat, lng=$lng" }
    }

    companion object {
        private val LATITUDES = -90.0..90.0
        private val LONGITUDES = -180.0..180.0

        fun isValid(
            lat: Double,
            lng: Double,
        ): Boolean = lat in LATITUDES && lng in LONGITUDES
    }
}
