package veeci.practicing.rts.shared.stats

import kotlin.math.floor

private const val HUNDRED = 100.0

/**
 * The [p]th percentile of [values] (p in 0..100), by linear interpolation between the closest ranks: the value at
 * rank `h = (n - 1) × p / 100` of the sorted list, interpolated between `floor(h)` and the next rank. This is
 * Excel's PERCENTILE.INC and NumPy's default, so a spreadsheet checks it. For 1..100, p50 is 50.5 and p95 is 95.05.
 * Null for an empty list.
 */
fun percentile(
    values: Collection<Double>,
    p: Double,
): Double? {
    require(p in 0.0..HUNDRED) { "p must be in 0..100, was $p" }
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val rank = (sorted.size - 1) * p / HUNDRED
    val below = floor(rank).toInt()
    val above = minOf(below + 1, sorted.lastIndex)
    return sorted[below] + (rank - below) * (sorted[above] - sorted[below])
}

/** One observation for [slope]. */
data class Point(
    val x: Double,
    val y: Double,
)

/**
 * The least-squares slope of y over x: `Σ(x - x̄)(y - ȳ) / Σ(x - x̄)²`. Null with fewer than two points, or when
 * every x is the same, because no line fits then.
 */
fun slope(points: List<Point>): Double? {
    if (points.size < 2) return null
    val meanX = points.sumOf { it.x } / points.size
    val meanY = points.sumOf { it.y } / points.size
    val spreadX = points.sumOf { (it.x - meanX) * (it.x - meanX) }
    return if (spreadX == 0.0) null else points.sumOf { (it.x - meanX) * (it.y - meanY) } / spreadX
}
