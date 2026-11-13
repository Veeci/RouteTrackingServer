package veeci.practicing.rts.routing.adapter.out.osm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import veeci.practicing.rts.testing.routing.GraphFixtures
import veeci.practicing.rts.testing.routing.edgePairs
import veeci.practicing.rts.testing.routing.edgeViews
import veeci.practicing.rts.testing.routing.osmIds
import java.nio.file.Path
import javax.xml.stream.XMLStreamException
import kotlin.io.path.writeText

class OsmXmlGraphLoaderTest {
    private fun loadXml(
        xml: String,
        prune: Boolean = false,
    ) = OsmXmlGraphLoader(prune).load(xml.byteInputStream())

    @Test
    fun `TC-6-GR-08 tiny_grid loads 9 nodes and 24 edges, and the footway and the lone node are ignored`() {
        val graph = GraphFixtures.load("tiny_grid")

        graph.nodeCount shouldBe 9
        graph.edgeCount shouldBe 24
        graph.osmIds() shouldBe (1L..9L).toSet()
        val lengths = graph.edgeViews().associate { (it.fromOsmId to it.toOsmId) to it.lengthM }
        // Expected values come from the comment block of tiny_grid.osm, computed outside this code.
        lengths.getValue(1L to 2L) shouldBe (103.789f plusOrMinus 0.001f)
        lengths.getValue(1L to 4L) shouldBe (111.195f plusOrMinus 0.001f)
        lengths.getValue(8L to 9L) shouldBe (103.790f plusOrMinus 0.001f)
    }

    @Test
    fun `TC-6-GR-05 two_islands keeps only the larger island`() {
        val pruned = GraphFixtures.load("two_islands")
        val full = GraphFixtures.load("two_islands", pruneToLargestComponent = false)

        pruned.osmIds() shouldBe setOf(1L, 2L, 3L, 4L)
        pruned.edgeCount shouldBe 8
        full.nodeCount shouldBe 7
        full.edgeCount shouldBe 14
    }

    @Test
    fun `TC-6-GR-03 a roundabout without a oneway tag is driven only in node order`() {
        val graph = GraphFixtures.load("roundabout")

        graph.nodeCount shouldBe 6
        graph.edgePairs() shouldBe
            setOf(
                1L to 2L,
                2L to 3L,
                3L to 4L,
                4L to 1L,
                5L to 1L,
                1L to 5L,
                3L to 6L,
                6L to 3L,
            )
    }

    @Test
    fun `TC-6-GR-02 one_way_trap has oneway=-1 as T to S only and oneway=yes as D to T only`() {
        val graph = GraphFixtures.load("one_way_trap")

        graph.edgePairs() shouldBe setOf(3L to 1L, 1L to 2L, 2L to 1L, 2L to 3L)
        // Way 201 is highway=secondary: 45 km/h.
        graph.edgeViews().single { it.fromOsmId == 3L }.speedMps shouldBe (12.5f plusOrMinus 0.001f)
    }

    @Test
    fun `nodes listed after the ways that use them are still found`() {
        val graph =
            loadXml(
                """
                <osm>
                  <way id="1"><nd ref="2"/><nd ref="1"/><tag k="highway" v="service"/></way>
                  <node id="1" lat="21.0" lon="105.8"/>
                  <node id="2" lat="21.001" lon="105.8"/>
                </osm>
                """.trimIndent(),
            )

        graph.edgePairs() shouldBe setOf(2L to 1L, 1L to 2L)
    }

    @Test
    fun `a node without lat names the line and the attribute`() {
        val error =
            shouldThrow<IllegalArgumentException> {
                loadXml("<osm>\n  <node id=\"1\" lon=\"105.8\"/>\n</osm>")
            }

        error.message shouldContain "line 2"
        error.message shouldContain "'lat'"
    }

    @Test
    fun `a coordinate that is not a number is refused`() {
        shouldThrow<IllegalArgumentException> { loadXml("""<osm><node id="1" lat="north" lon="105.8"/></osm>""") }
            .message shouldContain "'lat' of <node> is not a number"
    }

    @Test
    fun `an external entity is not expanded`(
        @TempDir dir: Path,
    ) {
        val secret = dir.resolve("secret.txt").apply { writeText("motorway") }
        val xml =
            """
            <?xml version="1.0"?>
            <!DOCTYPE osm [<!ENTITY road SYSTEM "${secret.toUri()}">]>
            <osm>
              <node id="1" lat="21.0" lon="105.8"/>
              <node id="2" lat="21.001" lon="105.8"/>
              <way id="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="&road;"/></way>
            </osm>
            """.trimIndent()

        // With DTD support off the entity is never declared, so the parser stops instead of reading the file into the tag.
        shouldThrow<XMLStreamException> { loadXml(xml) }.message shouldContain "\"road\" was referenced, but not declared"
    }

    @Test
    fun `loads from a file path`(
        @TempDir dir: Path,
    ) {
        val file =
            dir.resolve("small.osm").apply {
                writeText(
                    """
                    <osm>
                      <node id="1" lat="21.0" lon="105.8"/>
                      <node id="2" lat="21.001" lon="105.8"/>
                      <way id="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/></way>
                    </osm>
                    """.trimIndent(),
                )
            }

        OsmXmlGraphLoader().load(file).edgeCount shouldBe 2
    }
}
