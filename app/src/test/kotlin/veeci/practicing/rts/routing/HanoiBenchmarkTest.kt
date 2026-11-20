package veeci.practicing.rts.routing

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import veeci.practicing.rts.routing.adapter.out.osm.CachedGraphSource
import veeci.practicing.rts.routing.adapter.out.osm.OsmXmlGraphLoader
import veeci.practicing.rts.routing.application.LoadedRoadGraph
import veeci.practicing.rts.routing.domain.RoadGraph
import veeci.practicing.rts.routing.domain.search.AStar
import veeci.practicing.rts.routing.domain.search.BidirectionalDijkstra
import veeci.practicing.rts.routing.domain.search.Dijkstra
import veeci.practicing.rts.routing.domain.search.Metric
import veeci.practicing.rts.routing.domain.search.ShortestPath
import veeci.practicing.rts.testing.routing.sameAs
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * Slice 6d numbers on the real Hanoi extract (docs/data/osm.md). Run with `./gradlew slowTest`, which gives the JVM
 * the 512 MB heap of TC-6-GR-09. Every line that starts with `[6d]` goes into docs/plan/phase-06-routing.md.
 *
 * The tests run in order and share the graph that the first one parses.
 */
@Tag("slow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HanoiBenchmarkTest {
    private lateinit var osm: Path
    private lateinit var graph: RoadGraph

    @BeforeAll
    fun requireTheExtract() {
        val path = System.getProperty("rts.osm.hanoi")?.let(Path::of)
        assumeTrue(path != null && Files.isRegularFile(path)) {
            "Skipped: no Hanoi extract at $path. Download it with the curl command in docs/data/osm.md, then run ./gradlew slowTest."
        }
        osm = path!!
        report("OSM file: ${Files.size(osm) / MB} MB, JVM max heap ${Runtime.getRuntime().maxMemory() / MB} MB")
    }

    @Test
    @Order(1)
    fun `TC-6-GR-09 the loader builds the Hanoi graph in under 10 s and 512 MB of heap`() {
        val heap = HeapPeak.start()
        val (loaded, took) = measureTimedValue { OsmXmlGraphLoader().load(osm) }
        val peakMb = heap.peakMb()
        graph = loaded
        val indexTook = measureTime { LoadedRoadGraph(graph) }

        report("XML parse and build: ${graph.nodeCount} nodes, ${graph.edgeCount} edges in ${took.ms()} ms")
        report("Heap peak during the parse (sum of pool peaks, an upper bound): $peakMb MB")
        report("GridIndex and bounds: ${indexTook.ms()} ms")
        took.inWholeMilliseconds shouldBeLessThan LOAD_LIMIT_MS
        peakMb shouldBeLessThan HEAP_LIMIT_MB
    }

    @Test
    @Order(2)
    fun `the binary cache loads the same graph much faster than the XML`(
        @TempDir cacheDir: Path,
    ) {
        val source = CachedGraphSource(osm, cacheDir)
        val hashTook = measureTime { CachedGraphSource.sha256(osm) }
        val coldTook = measureTime { source.load() }
        val (cached, warmTook) = measureTimedValue { source.load() }
        // Run the warm path a few more times: the first read also pays for class loading and a cold page cache.
        val warmRuns = List(WARM_CACHE_RUNS) { measureTime { source.load() } }

        report("SHA-256 of the OSM file: ${hashTook.ms()} ms")
        report("Cache miss (hash, parse, build, write): ${coldTook.ms()} ms")
        report("Cache file: ${Files.size(source.cachePath) / KB} KB")
        report("Cache hit (hash and read): first ${warmTook.ms()} ms, then ${warmRuns.joinToString { it.ms() }} ms")
        cached.sameAs(graph).shouldBeTrue()
    }

    @Test
    @Order(3)
    fun `TC-6-SP-11 median A-star query on Hanoi is under 50 ms, and the three searches agree`() {
        val random = Random(SEED)
        val pairs = List(PAIRS) { random.nextInt(graph.nodeCount) to random.nextInt(graph.nodeCount) }
        val incomingTook = measureTime { graph.incoming }
        report("Reverse CSR for bidirectional, built on first use: ${incomingTook.ms()} ms")
        val searches = listOf("A*" to AStar(), "Dijkstra" to Dijkstra(), "Bidirectional" to BidirectionalDijkstra())

        for (metric in Metric.entries) {
            // Warm-up: let the JIT compile the search loops before anything is timed.
            repeat(WARM_UP_ROUNDS) {
                searches.forEach { (_, search) -> pairs.take(WARM_UP_PAIRS).forEach { search.search(graph, it.first, it.second, metric) } }
            }
            val stats = searches.map { (name, search) -> name to measure(search, pairs, metric) }
            stats.forEach { (name, s) -> report("$metric $name: ${s.line()}") }
            val costs = stats.map { it.second.costs }
            costs[1].indices.forEach { i ->
                costs[0][i] shouldBe (costs[1][i] plusOrMinus COST_TOLERANCE)
                costs[2][i] shouldBe (costs[1][i] plusOrMinus COST_TOLERANCE)
            }
            if (metric == Metric.DISTANCE) stats[0].second.medianMs shouldBeLessThan MEDIAN_LIMIT_MS
        }
    }

    private fun measure(
        search: ShortestPath,
        pairs: List<Pair<Int, Int>>,
        metric: Metric,
    ): Stats {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val times = DoubleArray(pairs.size)
        val settled = LongArray(pairs.size)
        val costs = DoubleArray(pairs.size)
        val allocatedBefore = threads.getThreadAllocatedBytes(thread)
        pairs.forEachIndexed { i, (from, to) ->
            val (result, took) = measureTimedValue { search.search(graph, from, to, metric) }
            val found = result.shouldNotBeNull()
            times[i] = took.toDouble(DurationUnit.MILLISECONDS)
            settled[i] = found.settled.toLong()
            costs[i] = if (metric == Metric.DISTANCE) found.costM else found.costS
        }
        val allocatedPerSearch = (threads.getThreadAllocatedBytes(thread) - allocatedBefore) / pairs.size
        times.sort()
        return Stats(times, settled.average(), allocatedPerSearch, costs)
    }

    private class Stats(
        val sortedMs: DoubleArray,
        val meanSettled: Double,
        val allocatedBytes: Long,
        val costs: DoubleArray,
    ) {
        val medianMs: Double get() = sortedMs[sortedMs.size / 2]

        fun line(): String =
            "median %.1f ms, p95 %.1f ms, max %.1f ms, mean settled %.0f, allocated %.1f MB per search".format(
                medianMs,
                sortedMs[(sortedMs.size * P95).toInt()],
                sortedMs.last(),
                meanSettled,
                allocatedBytes.toDouble() / MB,
            )
    }

    /** The heap pools' peak usage since [start]. Pools peak at different moments, so the sum is an upper bound. */
    private class HeapPeak private constructor() {
        private val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }

        fun peakMb(): Long = pools.sumOf { it.peakUsage.used } / MB

        companion object {
            @Suppress("ExplicitGarbageCollectionCall") // a clean baseline before the measured load
            fun start(): HeapPeak {
                System.gc()
                return HeapPeak().apply { pools.forEach { it.resetPeakUsage() } }
            }
        }
    }

    private fun report(line: String) = println("[6d] $line")

    private fun Duration.ms(): String = inWholeMilliseconds.toString()

    private companion object {
        const val KB = 1024L
        const val MB = 1024L * 1024
        const val LOAD_LIMIT_MS = 10_000L
        const val HEAP_LIMIT_MB = 512L
        const val MEDIAN_LIMIT_MS = 50.0
        const val SEED = 20_261_120L
        const val PAIRS = 100
        const val WARM_UP_ROUNDS = 3
        const val WARM_UP_PAIRS = 30
        const val WARM_CACHE_RUNS = 3
        const val P95 = 0.95
        const val COST_TOLERANCE = 1e-6
    }
}
