package veeci.practicing.rts.shared.geo

@JvmInline
value class Meters(
    val value: Double,
) : Comparable<Meters> {
    operator fun plus(other: Meters) = Meters(value + other.value)

    operator fun minus(other: Meters) = Meters(value - other.value)

    override fun compareTo(other: Meters) = value.compareTo(other.value)

    override fun toString() = "$value m"
}
