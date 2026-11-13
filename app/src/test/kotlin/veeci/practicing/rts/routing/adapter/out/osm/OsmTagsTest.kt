package veeci.practicing.rts.routing.adapter.out.osm

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.routing.domain.HighwayType
import veeci.practicing.rts.routing.domain.Oneway

class OsmTagsTest {
    private fun oneway(vararg tags: Pair<String, String>) = OsmTags.oneway(mapOf("highway" to "residential") + tags)

    @Test
    fun `TC-6-GR-02 oneway yes, true and 1 are forward, -1 is reverse, no tag is both`() {
        oneway("oneway" to "yes") shouldBe Oneway.FORWARD
        oneway("oneway" to "true") shouldBe Oneway.FORWARD
        oneway("oneway" to "1") shouldBe Oneway.FORWARD
        oneway("oneway" to "-1") shouldBe Oneway.REVERSE
        oneway() shouldBe Oneway.NO
        oneway("oneway" to "reversible") shouldBe Oneway.NO
    }

    @Test
    fun `TC-6-GR-03 junction=roundabout or circular without a oneway tag is forward`() {
        oneway("junction" to "roundabout") shouldBe Oneway.FORWARD
        oneway("junction" to "circular") shouldBe Oneway.FORWARD
    }

    @Test
    fun `an explicit oneway tag wins over a roundabout`() {
        oneway("junction" to "roundabout", "oneway" to "no") shouldBe Oneway.NO
        oneway("junction" to "roundabout", "oneway" to "-1") shouldBe Oneway.REVERSE
    }

    @Test
    fun `motorways and their links are one-way unless tagged otherwise`() {
        OsmTags.oneway(mapOf("highway" to "motorway")) shouldBe Oneway.FORWARD
        OsmTags.oneway(mapOf("highway" to "motorway_link")) shouldBe Oneway.FORWARD
        OsmTags.oneway(mapOf("highway" to "motorway", "oneway" to "no")) shouldBe Oneway.NO
        OsmTags.oneway(mapOf("highway" to "trunk")) shouldBe Oneway.NO
    }

    @Test
    fun `link roads take the class of their road and paths are skipped`() {
        OsmTags.highwayType("primary_link") shouldBe HighwayType.PRIMARY
        OsmTags.highwayType("living_street") shouldBe HighwayType.LIVING_STREET
        OsmTags.highwayType("footway") shouldBe null
        OsmTags.highwayType("cycleway") shouldBe null
        OsmTags.highwayType(null) shouldBe null
    }
}
