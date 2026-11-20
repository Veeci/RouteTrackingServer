package veeci.practicing.rts.routing.adapter.out.osm

import veeci.practicing.rts.routing.domain.RoadGraph
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32C

/**
 * The binary form of a built [RoadGraph]: its CSR arrays as they are after SCC pruning, so a read needs no XML
 * parse, no builder and no pruning.
 *
 * Layout, little-endian:
 *
 * | Field | Size |
 * |---|---|
 * | magic `RTSG` | 4 |
 * | format version | 4 |
 * | SHA-256 of the OSM file the graph came from | 32 |
 * | node count `n`, edge count `m` | 4 + 4 |
 * | OSM ids, latitudes, longitudes | 8n each |
 * | edge offsets | 4(n + 1) |
 * | edge targets, lengths, speeds | 4m each |
 * | CRC32C of the array bytes | 8 |
 *
 * Each array moves in one bulk copy between a heap [ByteBuffer] and the primitive array, and one [FileChannel]
 * call moves the buffer to or from the disk. The file is read into plain arrays, not memory-mapped: the graph
 * keeps the same type whichever way it was loaded, and a few tens of MB fit the heap with room to spare.
 *
 * Bump [FORMAT_VERSION] when the layout changes, and also when the graph a given OSM file builds changes, such as
 * new default speeds in `HighwayType` or new rules in `RoadGraphBuilder`. The hash only proves the input is the
 * same file, not that the code would build the same graph from it.
 */
class RoadGraphCacheFile(
    private val formatVersion: Int = FORMAT_VERSION,
) {
    /** Writes [graph] to a temporary file next to [target], then renames it over [target] in one step. */
    fun write(
        graph: RoadGraph,
        osmSha256: ByteArray,
        target: Path,
    ) {
        require(osmSha256.size == SHA256_BYTES) { "A SHA-256 has $SHA256_BYTES bytes, got ${osmSha256.size}" }
        val temp = Files.createTempFile(target.toAbsolutePath().parent, "${target.fileName}.", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                writeTo(channel, graph, osmSha256)
                channel.force(true)
            }
            // A reader sees the old file or the new one, never half of one, even if the process dies mid-write.
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Returns the graph in [path] when its header matches [osmSha256] and this format version, or null when it
     * does not (a stale cache).
     * @throws IOException when the file is missing, truncated or corrupt.
     */
    fun read(
        path: Path,
        osmSha256: ByteArray,
    ): RoadGraph? =
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val header = channel.readExactly(HEADER_BYTES.toLong())
            if (header.int != MAGIC) throw CorruptCacheException("the file does not start with the RTSG magic number")
            if (header.int != formatVersion) return null
            val hash = ByteArray(SHA256_BYTES).also(header::get)
            if (!hash.contentEquals(osmSha256)) return null
            readBody(channel, nodes = header.int, edges = header.int)
        }

    private fun writeTo(
        channel: FileChannel,
        graph: RoadGraph,
        osmSha256: ByteArray,
    ) {
        val n = graph.nodeCount
        val m = graph.edgeCount
        val header =
            buffer(HEADER_BYTES.toLong())
                .putInt(MAGIC)
                .putInt(formatVersion)
                .put(osmSha256)
                .putInt(n)
                .putInt(m)
                .flip()
        channel.writeFully(header)
        val crc = CRC32C()
        channel.writeBlock(crc, buffer(LONG * n).apply { asLongBuffer().put(graph.nodeOsmIds) })
        channel.writeBlock(crc, buffer(DOUBLE * n).apply { asDoubleBuffer().put(graph.nodeLat) })
        channel.writeBlock(crc, buffer(DOUBLE * n).apply { asDoubleBuffer().put(graph.nodeLng) })
        channel.writeBlock(crc, buffer(INT * (n + 1L)).apply { asIntBuffer().put(graph.edgeOffsets) })
        channel.writeBlock(crc, buffer(INT * m).apply { asIntBuffer().put(graph.edgeTargets) })
        channel.writeBlock(crc, buffer(FLOAT * m).apply { asFloatBuffer().put(graph.edgeLengthM) })
        channel.writeBlock(crc, buffer(FLOAT * m).apply { asFloatBuffer().put(graph.edgeSpeedMps) })
        channel.writeFully(buffer(LONG).putLong(crc.value).flip())
    }

    private fun readBody(
        channel: FileChannel,
        nodes: Int,
        edges: Int,
    ): RoadGraph {
        checkSize(channel, nodes, edges)
        val crc = CRC32C()
        val osmIds = LongArray(nodes).also { channel.readBlock(crc, LONG * nodes).asLongBuffer().get(it) }
        val lats = DoubleArray(nodes).also { channel.readBlock(crc, DOUBLE * nodes).asDoubleBuffer().get(it) }
        val lngs = DoubleArray(nodes).also { channel.readBlock(crc, DOUBLE * nodes).asDoubleBuffer().get(it) }
        val offsets = IntArray(nodes + 1).also { channel.readBlock(crc, INT * (nodes + 1L)).asIntBuffer().get(it) }
        val targets = IntArray(edges).also { channel.readBlock(crc, INT * edges).asIntBuffer().get(it) }
        val lengths = FloatArray(edges).also { channel.readBlock(crc, FLOAT * edges).asFloatBuffer().get(it) }
        val speeds = FloatArray(edges).also { channel.readBlock(crc, FLOAT * edges).asFloatBuffer().get(it) }
        if (channel.readExactly(LONG).long != crc.value) throw CorruptCacheException("the checksum does not match the arrays")
        return try {
            RoadGraph(osmIds, lats, lngs, offsets, targets, lengths, speeds)
        } catch (e: IllegalArgumentException) {
            throw CorruptCacheException("the arrays do not form a graph: ${e.message}", e)
        }
    }

    /** Checking the size before reading also stops a corrupt count from allocating gigabytes. */
    private fun checkSize(
        channel: FileChannel,
        nodes: Int,
        edges: Int,
    ) {
        if (nodes < 0 || edges < 0) throw CorruptCacheException("negative counts: $nodes nodes, $edges edges")
        val expectedSize = HEADER_BYTES + bodyBytes(nodes.toLong(), edges.toLong()) + LONG
        if (channel.size() != expectedSize) {
            throw CorruptCacheException("the file has ${channel.size()} bytes, its header says $expectedSize")
        }
    }

    /** Thrown when a cache file cannot be read back into a graph. The caller then parses the XML. */
    class CorruptCacheException(
        message: String,
        cause: Throwable? = null,
    ) : IOException("Corrupt road graph cache: $message", cause)

    companion object {
        const val MAGIC: Int = 0x52545347 // "RTSG"
        const val FORMAT_VERSION: Int = 1
        private const val SHA256_BYTES = 32
        private const val INT = 4L
        private const val FLOAT = 4L
        private const val LONG = 8L
        private const val DOUBLE = 8L
        private const val HEADER_BYTES = 4 + 4 + SHA256_BYTES + 4 + 4

        private fun bodyBytes(
            n: Long,
            m: Long,
        ): Long = (LONG + DOUBLE + DOUBLE) * n + INT * (n + 1) + (INT + FLOAT + FLOAT) * m

        private fun buffer(bytes: Long): ByteBuffer = ByteBuffer.allocate(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN)

        private fun FileChannel.writeFully(buffer: ByteBuffer) {
            while (buffer.hasRemaining()) write(buffer)
        }

        /** The bulk put into a view buffer leaves [block]'s own position at 0, so the whole block is written. */
        private fun FileChannel.writeBlock(
            crc: CRC32C,
            block: ByteBuffer,
        ) {
            crc.update(block.duplicate())
            writeFully(block)
        }

        private fun FileChannel.readExactly(bytes: Long): ByteBuffer {
            val buffer = buffer(bytes)
            while (buffer.hasRemaining()) {
                if (read(buffer) < 0) throw CorruptCacheException("the file ends early")
            }
            return buffer.flip()
        }

        private fun FileChannel.readBlock(
            crc: CRC32C,
            bytes: Long,
        ): ByteBuffer = readExactly(bytes).also { crc.update(it.duplicate()) }
    }
}
