package veeci.practicing.rts.routing.adapter.out.osm

import org.slf4j.LoggerFactory
import veeci.practicing.rts.routing.application.port.out.RoadGraphSource
import veeci.practicing.rts.routing.domain.RoadGraph
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream
import kotlin.io.path.nameWithoutExtension
import kotlin.time.TimeSource

/**
 * Reads the graph of an OSM file through a binary cache in [cacheDir] (docs/data/osm.md).
 *
 * 1. Hash the OSM file with SHA-256.
 * 2. When `<cacheDir>/<name>.graph` has the same hash and format version, read it. This takes well under a second.
 * 3. Otherwise parse the XML and build the graph, as [OsmFileGraphSource] does, then write the cache for next time.
 *
 * The cache is only ever an optimisation. A stale, truncated or corrupt cache is parsed around, and a cache
 * directory that cannot be written costs only the next start's time: both log a WARN and return the parsed graph.
 */
class CachedGraphSource(
    private val osmPath: Path,
    private val cacheDir: Path,
    /** Builds the graph from the XML on a cache miss. A parameter so tests can count the parses. */
    private val parse: (Path) -> RoadGraph = OsmXmlGraphLoader()::load,
    private val cacheFile: RoadGraphCacheFile = RoadGraphCacheFile(),
) : RoadGraphSource {
    private val log = LoggerFactory.getLogger(CachedGraphSource::class.java)

    /** The cache file for [osmPath]: `hanoi.osm` gives `<cacheDir>/hanoi.graph`. */
    val cachePath: Path = cacheDir.resolve("${osmPath.nameWithoutExtension}.graph")

    override val description: String get() = "${osmPath.toAbsolutePath()} (cache ${cachePath.toAbsolutePath()})"

    override fun load(): RoadGraph {
        val hash = sha256(osmPath)
        readCache(hash)?.let { return it }
        val started = TimeSource.Monotonic.markNow()
        val graph = parse(osmPath)
        log.info("Parsed the road graph from the OSM XML {} in {}", osmPath, started.elapsedNow())
        writeCache(graph, hash)
        return graph
    }

    private fun readCache(hash: ByteArray): RoadGraph? {
        val started = TimeSource.Monotonic.markNow()
        return try {
            cacheFile.read(cachePath, hash).also { graph ->
                if (graph != null) {
                    log.info("Read the road graph from the cache {} in {}", cachePath, started.elapsedNow())
                } else {
                    log.info("The road graph cache {} is for another OSM file or format version; rebuilding it", cachePath)
                }
            }
        } catch (_: NoSuchFileException) {
            log.info("No road graph cache at {} yet; it is written after this parse", cachePath)
            null
        } catch (e: IOException) {
            log.warn("Could not read the road graph cache {}; parsing the OSM XML instead", cachePath, e)
            null
        }
    }

    private fun writeCache(
        graph: RoadGraph,
        hash: ByteArray,
    ) {
        val started = TimeSource.Monotonic.markNow()
        try {
            Files.createDirectories(cacheDir)
            cacheFile.write(graph, hash, cachePath)
            log.info("Wrote the road graph cache {} ({} bytes) in {}", cachePath, Files.size(cachePath), started.elapsedNow())
        } catch (e: IOException) {
            log.warn("Could not write the road graph cache {}; the next start parses the XML again", cachePath, e)
        }
    }

    internal companion object {
        private const val READ_BUFFER_BYTES = 1 shl 20

        fun sha256(path: Path): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(READ_BUFFER_BYTES)
            path.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest()
        }
    }
}
