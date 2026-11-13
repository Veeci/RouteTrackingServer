package veeci.practicing.rts.routing.adapter.out.osm

import veeci.practicing.rts.routing.domain.HighwayType
import veeci.practicing.rts.routing.domain.Oneway

/**
 * Turns OSM tags into the graph's road class and direction. This lives in the adapter because the tag
 * names and values are OSM's vocabulary; the domain only knows [HighwayType] and [Oneway].
 * The rules are listed in docs/data/osm.md.
 */
internal object OsmTags {
    private val highwayTypes =
        mapOf(
            "motorway" to HighwayType.MOTORWAY,
            "trunk" to HighwayType.TRUNK,
            "primary" to HighwayType.PRIMARY,
            "secondary" to HighwayType.SECONDARY,
            "tertiary" to HighwayType.TERTIARY,
            "unclassified" to HighwayType.UNCLASSIFIED,
            "residential" to HighwayType.RESIDENTIAL,
            "living_street" to HighwayType.LIVING_STREET,
            "service" to HighwayType.SERVICE,
        )

    private val forward = setOf("yes", "true", "1")
    private val both = setOf("no", "false", "0")
    private val impliedOnewayJunctions = setOf("roundabout", "circular")
    private val impliedOnewayHighways = setOf("motorway", "motorway_link")

    /** The road class of a `highway` value, `_link` roads included; null for roads the graph skips (footway, path, ...). */
    fun highwayType(highway: String?): HighwayType? = highway?.removeSuffix("_link")?.let(highwayTypes::get)

    /** An explicit `oneway` tag wins. Without one, roundabouts and motorways are one-way, as OSM defines them. */
    fun oneway(tags: Map<String, String>): Oneway {
        val value = tags["oneway"]
        return when {
            value in forward -> Oneway.FORWARD
            value == "-1" -> Oneway.REVERSE
            value in both -> Oneway.NO
            tags["junction"] in impliedOnewayJunctions || tags["highway"] in impliedOnewayHighways -> Oneway.FORWARD
            else -> Oneway.NO
        }
    }
}
