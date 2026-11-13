package veeci.practicing.rts.testing.routing

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import veeci.practicing.rts.routing.domain.HighwayType
import veeci.practicing.rts.routing.domain.Oneway
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.routing.domain.RoadGraphBuilder
import veeci.practicing.rts.shared.geo.GeoPoint

/**
 * Random street networks that look like a city: a grid of `columns x rows` junctions about 110 m apart
 * near Hoan Kiem Lake, each moved by up to 30 m. Neighbouring junctions are joined by a street of a random
 * type. About [oneWayShare] of the streets are one-way and about 10% are missing. The graph is pruned to its
 * largest strongly connected component, so every node reaches every other.
 *
 * Kotest prints the seed when a property fails. Pass it to `PropTestConfig(seed = ...)` to replay the case.
 */
fun Arb.Companion.roadGraph(
    sizes: IntRange = 3..12,
    oneWayShare: Double = 0.2,
): Arb<RoadGraph> =
    arbitrary { rs ->
        val columns = Arb.int(sizes).bind()
        val rows = Arb.int(sizes).bind()
        val random = rs.random
        val builder = RoadGraphBuilder()
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val lat = BASE_LAT + r * STEP_DEG + random.nextDouble(-JITTER_DEG, JITTER_DEG)
                val lng = BASE_LNG + c * STEP_DEG + random.nextDouble(-JITTER_DEG, JITTER_DEG)
                builder.addNode(id(c, r, columns), GeoPoint(lat, lng))
            }
        }

        fun street(
            a: Long,
            b: Long,
        ) {
            val roll = random.nextDouble()
            val oneway =
                when {
                    roll < MISSING_SHARE -> return
                    roll < MISSING_SHARE + oneWayShare / 2 -> Oneway.FORWARD
                    roll < MISSING_SHARE + oneWayShare -> Oneway.REVERSE
                    else -> Oneway.NO
                }
            builder.addWay(listOf(a, b), HighwayType.entries.random(random), oneway)
        }
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                if (c + 1 < columns) street(id(c, r, columns), id(c + 1, r, columns))
                if (r + 1 < rows) street(id(c, r, columns), id(c, r + 1, columns))
            }
        }
        builder.build()
    }

private fun id(
    column: Int,
    row: Int,
    columns: Int,
): Long = (row * columns + column).toLong()

private const val BASE_LAT = 21.020
private const val BASE_LNG = 105.840
private const val STEP_DEG = 0.001
private const val JITTER_DEG = 0.0003
private const val MISSING_SHARE = 0.1
