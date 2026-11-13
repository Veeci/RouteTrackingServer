package veeci.practicing.rts.routing.domain

/**
 * The road classes the graph keeps, with the default speed used for the TIME metric.
 * The speeds are planning defaults for city traffic, not legal limits (docs/plan/phase-06-routing.md).
 */
enum class HighwayType(
    val speedKmh: Int,
) {
    MOTORWAY(speedKmh = 80),
    TRUNK(speedKmh = 70),
    PRIMARY(speedKmh = 50),
    SECONDARY(speedKmh = 45),
    TERTIARY(speedKmh = 40),
    UNCLASSIFIED(speedKmh = 30),
    RESIDENTIAL(speedKmh = 30),
    LIVING_STREET(speedKmh = 15),
    SERVICE(speedKmh = 15),
    ;

    val speedMps: Float get() = (speedKmh / KMH_PER_MPS).toFloat()

    private companion object {
        const val KMH_PER_MPS = 3.6
    }
}
