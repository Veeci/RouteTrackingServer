package veeci.practicing.rts.routing.adapter.out.osm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.testing.Fixtures
import veeci.practicing.rts.testing.routing.sameAs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** T6.10: the binary graph cache. Each test copies a fixture OSM file into a temp directory and caches it there. */
class CachedGraphSourceTest {
    @TempDir
    lateinit var dir: Path

    /** Counts XML parses, so a test can tell a cache hit from a rebuild. */
    private class CountingLoader : (Path) -> RoadGraph {
        var parses = 0

        override fun invoke(path: Path): RoadGraph = OsmXmlGraphLoader().load(path).also { parses++ }
    }

    private fun osmFile(fixture: String = "tiny_grid"): Path =
        dir.resolve("city.osm").apply { writeText(Fixtures.text("graphs/$fixture.osm")) }

    private fun source(
        osm: Path,
        cacheDir: Path = dir.resolve("cache"),
        cacheFile: RoadGraphCacheFile = RoadGraphCacheFile(),
        counting: CountingLoader? = null,
    ): CachedGraphSource = CachedGraphSource(osm, cacheDir, counting ?: OsmXmlGraphLoader()::load, cacheFile)

    @ParameterizedTest
    @ValueSource(strings = ["tiny_grid", "one_way_trap", "two_islands", "roundabout", "saigon_test_grid"])
    fun `a graph written to the cache reads back equal to the parsed one`(fixture: String) {
        val osm = osmFile(fixture)
        val parsed = OsmXmlGraphLoader().load(osm)
        val file = dir.resolve("$fixture.graph")
        val hash = CachedGraphSource.sha256(osm)

        RoadGraphCacheFile().write(parsed, hash, file)
        val read = RoadGraphCacheFile().read(file, hash)!!

        read.sameAs(parsed).shouldBeTrue()
        read.maxSpeedMps shouldBe parsed.maxSpeedMps
    }

    @Test
    fun `the first load parses the XML and writes the cache, the second reads the cache`() {
        val osm = osmFile()
        val counting = CountingLoader()
        val source = source(osm, counting = counting)

        val first = source.load()
        source.cachePath.exists().shouldBeTrue()
        val second = source.load()

        counting.parses shouldBe 1
        second.sameAs(first).shouldBeTrue()
        source.cachePath.fileName.toString() shouldBe "city.graph"
        // The temporary file was renamed, not left behind.
        source.cachePath.parent
            .listDirectoryEntries()
            .map { it.fileName.toString() } shouldBe listOf("city.graph")
    }

    @Test
    fun `a changed OSM file rebuilds the cache`() {
        val osm = osmFile("tiny_grid")
        val counting = CountingLoader()
        val source = source(osm, counting = counting)
        source.load()

        osm.writeText(Fixtures.text("graphs/roundabout.osm"))
        val rebuilt = source.load()
        val again = source.load()

        counting.parses shouldBe 2
        rebuilt.nodeCount shouldBe 6
        again.sameAs(rebuilt).shouldBeTrue()
    }

    @Test
    fun `a cache of another format version is rebuilt`() {
        val osm = osmFile()
        source(osm, cacheFile = RoadGraphCacheFile(formatVersion = RoadGraphCacheFile.FORMAT_VERSION - 1)).load()
        val counting = CountingLoader()
        val current = source(osm, counting = counting)

        RoadGraphCacheFile().read(current.cachePath, CachedGraphSource.sha256(osm)).shouldBeNull()
        current.load()
        current.load()

        counting.parses shouldBe 1
    }

    @Test
    fun `a truncated cache falls back to the XML and is rewritten`() {
        val osm = osmFile()
        val source = source(osm)
        val parsed = source.load()
        val bytes = source.cachePath.readBytes()
        source.cachePath.writeBytes(bytes.copyOf(bytes.size - 20))
        val counting = CountingLoader()

        val loaded = source(osm, counting = counting).load()

        counting.parses shouldBe 1
        loaded.sameAs(parsed).shouldBeTrue()
        source.cachePath.readBytes().size shouldBe bytes.size
    }

    @Test
    fun `a corrupt cache falls back to the XML`() {
        val osm = osmFile()
        val source = source(osm)
        val parsed = source.load()
        val bytes = source.cachePath.readBytes()
        // Flip one byte inside the latitudes: the size still matches, so only the checksum can catch it.
        bytes[HEADER_BYTES + 9 * 8 + 3] = (bytes[HEADER_BYTES + 9 * 8 + 3] + 1).toByte()
        source.cachePath.writeBytes(bytes)

        shouldThrow<IOException> { RoadGraphCacheFile().read(source.cachePath, CachedGraphSource.sha256(osm)) }
        val counting = CountingLoader()
        val loaded = source(osm, counting = counting).load()

        counting.parses shouldBe 1
        loaded.sameAs(parsed).shouldBeTrue()
    }

    @Test
    fun `a file that is not a graph cache, or is shorter than the header, falls back to the XML`() {
        val osm = osmFile()
        val source = source(osm)
        Files.createDirectories(source.cachePath.parent)

        source.cachePath.writeText("this is not a graph cache file, only some text. ".repeat(4))
        source.load().nodeCount shouldBe 9
        source.cachePath.writeBytes(ByteArray(3))
        shouldThrow<IOException> { RoadGraphCacheFile().read(source.cachePath, CachedGraphSource.sha256(osm)) }
        source.load().nodeCount shouldBe 9
    }

    @Test
    fun `a cache directory that cannot be created still loads the graph`() {
        val osm = osmFile()
        // A regular file where the directory should be: createDirectories fails, even for root.
        val blocked = dir.resolve("blocked").apply { writeText("a file, not a directory") }
        val counting = CountingLoader()
        val source = source(osm, cacheDir = blocked.resolve("cache"), counting = counting)

        source.load().nodeCount shouldBe 9
        source.load().nodeCount shouldBe 9

        counting.parses shouldBe 2
        source.cachePath.exists() shouldBe false
    }

    @Test
    fun `a missing OSM file is a failed load even when a cache exists`() {
        val osm = osmFile()
        val source = source(osm)
        source.load()
        Files.delete(osm)

        shouldThrow<IOException> { source.load() }
    }

    @Test
    fun `different OSM files hash differently`() {
        val a = CachedGraphSource.sha256(osmFile("tiny_grid"))
        val b = CachedGraphSource.sha256(osmFile("roundabout"))

        a.size shouldBe 32
        a shouldNotBe b
    }

    private companion object {
        /** Magic, version, SHA-256, node count, edge count. */
        const val HEADER_BYTES = 4 + 4 + 32 + 4 + 4
    }
}
