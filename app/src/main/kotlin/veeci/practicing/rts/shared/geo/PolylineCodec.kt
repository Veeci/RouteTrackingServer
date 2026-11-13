package veeci.practicing.rts.shared.geo

/**
 * Google Encoded Polyline Algorithm Format, precision 5 (about 1 m):
 * https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 *
 * Each coordinate is rounded to an integer number of 1e-5 degrees, and each point is stored as the
 * difference from the one before it, so a route of nearby points becomes a string of small numbers.
 * A number is zig-zag encoded (sign moved to the lowest bit), cut into 5-bit chunks from the low end,
 * and each chunk becomes one printable ASCII character: `chunk + 63`, with `0x20` set when more chunks follow.
 * Android's `PolyUtil` and the Google Maps SDKs decode the same format.
 */
object PolylineCodec {
    private const val SCALE = 1e5
    private const val CHUNK_BITS = 5
    private const val CHUNK_MASK = 0x1f
    private const val MORE_CHUNKS = 0x20
    private const val ASCII_OFFSET = 63

    /** A Long holds any value up to 64 bits, which 13 chunks of 5 bits cover. Longer input is malformed. */
    private const val MAX_SHIFT = 64

    fun encode(points: List<GeoPoint>): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLng = 0L
        for (point in points) {
            val lat = Math.round(point.lat * SCALE)
            val lng = Math.round(point.lng * SCALE)
            appendSigned(out, lat - lastLat)
            appendSigned(out, lng - lastLng)
            lastLat = lat
            lastLng = lng
        }
        return out.toString()
    }

    /** @throws IllegalArgumentException when [encoded] is not a well-formed polyline of valid positions. */
    fun decode(encoded: String): List<GeoPoint> {
        val points = ArrayList<GeoPoint>()
        val reader = Reader(encoded)
        var lat = 0L
        var lng = 0L
        while (reader.hasMore()) {
            lat += reader.nextSigned()
            require(reader.hasMore()) { "Polyline ends after a latitude with no longitude" }
            lng += reader.nextSigned()
            points += GeoPoint(lat / SCALE, lng / SCALE)
        }
        return points
    }

    private fun appendSigned(
        out: StringBuilder,
        value: Long,
    ) {
        // Zig-zag: 0, -1, 1, -2, 2 become 0, 1, 2, 3, 4, so small negative numbers stay short.
        var rest = if (value < 0) (value shl 1).inv() else value shl 1
        while (rest >= MORE_CHUNKS) {
            out.append(((MORE_CHUNKS or (rest and CHUNK_MASK.toLong()).toInt()) + ASCII_OFFSET).toChar())
            rest = rest ushr CHUNK_BITS
        }
        out.append((rest.toInt() + ASCII_OFFSET).toChar())
    }

    private class Reader(
        private val text: String,
    ) {
        private var index = 0

        fun hasMore(): Boolean = index < text.length

        fun nextSigned(): Long {
            var result = 0L
            var shift = 0
            do {
                require(index < text.length) { "Polyline ends in the middle of a number" }
                val chunk = text[index++].code - ASCII_OFFSET
                require(chunk in 0..(MORE_CHUNKS or CHUNK_MASK)) { "Character at ${index - 1} is not polyline data" }
                require(shift < MAX_SHIFT) { "Number at ${index - 1} is too long" }
                result = result or ((chunk and CHUNK_MASK).toLong() shl shift)
                shift += CHUNK_BITS
            } while (chunk >= MORE_CHUNKS)
            return if (result and 1L != 0L) (result ushr 1).inv() else result ushr 1
        }
    }
}
