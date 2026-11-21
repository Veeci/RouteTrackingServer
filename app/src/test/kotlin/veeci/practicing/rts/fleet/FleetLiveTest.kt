package veeci.practicing.rts.fleet

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import veeci.practicing.rts.fleet.application.FleetLive
import veeci.practicing.rts.fleet.application.FleetSink
import veeci.practicing.rts.fleet.application.FleetUpdate
import veeci.practicing.rts.fleet.application.port.out.FleetRepository
import veeci.practicing.rts.fleet.domain.Bbox
import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetCounts
import veeci.practicing.rts.fleet.domain.FleetCursor
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.fleet.domain.FleetFilter
import veeci.practicing.rts.fleet.domain.MapCell
import veeci.practicing.rts.fleet.domain.MapView
import veeci.practicing.rts.platform.config.FleetConfig
import veeci.practicing.rts.platform.db.TransactionRunner
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** The live map's tick and its subscribers, against an in-memory table and a movable clock. */
class FleetLiveTest {
    private var now = Instant.parse("2026-11-17T08:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now.toJavaInstant()
        }
    private val table = InMemoryFleet { now }
    private val area = Bbox(105.80, 21.00, 105.90, 21.10)
    private var live = live()

    @AfterEach
    fun stop() = runBlocking { live.stop() }

    @Test
    fun `TC-11-FL-03 fifty fixes between two ticks reach the subscriber as one upsert with the last position`() =
        runBlocking<Unit> {
            val driver = driver(lat = 21.05, lng = 105.85)
            table.write(driver)
            val sink = RecordingSink()
            live.open(sink).subscribe(MapView(area))
            sink.updates
                .single()
                .shouldBeInstanceOf<FleetUpdate.Snapshot>()
                .markers shouldHaveSize 1

            repeat(50) { i ->
                now += 40.milliseconds
                table.write(driver.moved(lat = 21.05 + i * 0.0001))
            }
            tick()

            val delta = sink.updates.last().shouldBeInstanceOf<FleetUpdate.Delta>()
            delta.upserts shouldHaveSize 1
            delta.upserts.single().lat shouldBe 21.05 + 49 * 0.0001
            delta.removes.shouldBeEmpty()
        }

    @Test
    fun `an unchanged row read again by the overlap is not sent twice, and a driver leaving the area is removed`() =
        runBlocking<Unit> {
            val driver = driver(lat = 21.05, lng = 105.85)
            table.write(driver)
            val sink = RecordingSink()
            live.open(sink).subscribe(MapView(area))

            now += 1.seconds
            tick()
            sink.lastDelta().upserts.shouldBeEmpty()

            now += 1.seconds
            table.write(driver.moved(lat = 22.0))
            tick()
            sink.lastDelta().removes shouldContainExactly listOf(driver.driverId)

            now += 1.seconds
            tick()
            sink.lastDelta().removes.shouldBeEmpty()
        }

    @Test
    fun `a driver who goes silent is sent as offline although no row changed`() =
        runBlocking<Unit> {
            val driver = driver(lat = 21.05, lng = 105.85)
            table.write(driver)
            val all = RecordingSink()
            val onlineOnly = RecordingSink()
            live.open(all).subscribe(MapView(area))
            live.open(onlineOnly).subscribe(MapView(area, online = true))

            now += 61.seconds
            tick()

            all
                .lastDelta()
                .upserts
                .single()
                .online shouldBe false
            onlineOnly.lastDelta().removes shouldContainExactly listOf(driver.driverId)
        }

    @Test
    fun `a crowded area is sent as clusters every tick, and as markers again when it empties`() =
        runBlocking<Unit> {
            live = live(maxMarkers = 2)
            val drivers = listOf(driver(21.01, 105.81), driver(21.02, 105.82), driver(21.09, 105.89))
            drivers.forEach(table::write)
            val sink = RecordingSink()
            live.open(sink).subscribe(MapView(area))

            sink.updates
                .last()
                .shouldBeInstanceOf<FleetUpdate.Clusters>()
                .total shouldBe 3
            now += 1.seconds
            tick()
            sink.updates
                .last()
                .shouldBeInstanceOf<FleetUpdate.Clusters>()
                .total shouldBe 3

            now += 1.seconds
            table.write(drivers.last().moved(lat = 30.0))
            tick()
            sink.updates
                .last()
                .shouldBeInstanceOf<FleetUpdate.Snapshot>()
                .markers shouldHaveSize 2
        }

    @Test
    fun `a socket that takes no message within the send timeout is told it is too slow and gets nothing more`() =
        runBlocking<Unit> {
            live = live(sendTimeout = 50.milliseconds)
            table.write(driver(21.05, 105.85))
            val stuck = StuckSink()

            live.open(stuck).subscribe(MapView(area))
            stuck.tooSlow shouldBe 1

            now += 1.seconds
            tick()
            stuck.tooSlow shouldBe 1
        }

    @Test
    fun `a read that throws CancellationException while the tick loop runs is logged and the next tick still runs`() =
        runBlocking<Unit> {
            table.write(driver(21.05, 105.85))
            live.open(RecordingSink()).subscribe(MapView(area))
            table.cancelNextRead = true
            val loop = CoroutineScope(Job())
            try {
                live.start(loop)

                // The tick is 100 ms. Before the fix the first read ended the loop, so a second read never came.
                withTimeout(5.seconds) { while (table.reads < 2) delay(10.milliseconds) }
                table.cancelNextRead shouldBe false
            } finally {
                loop.cancel()
            }
        }

    private suspend fun tick() = live.tick().joinAll()

    private fun live(
        maxMarkers: Int = 5_000,
        sendTimeout: kotlin.time.Duration = 5.seconds,
    ) = FleetLive(table, DirectTransactions, clock, 60.seconds, FleetConfig(100.milliseconds, maxMarkers, sendTimeout))

    private fun driver(
        lat: Double,
        lng: Double,
    ) = FleetDriver(
        driverId = UUID.randomUUID(),
        position = DriverPosition(lat, lng, 8.0, 90.0, now),
        device = DeviceState(80, false, null, now),
    )

    private fun FleetDriver.moved(lat: Double) = copy(position = position!!.copy(lat = lat, at = now))

    private class RecordingSink : FleetSink {
        val updates = mutableListOf<FleetUpdate>()

        override suspend fun send(update: FleetUpdate) {
            updates += update
        }

        override fun tooSlow() = error("not expected")

        fun lastDelta() = updates.last().shouldBeInstanceOf<FleetUpdate.Delta>()
    }

    private class StuckSink : FleetSink {
        var tooSlow = 0

        override suspend fun send(update: FleetUpdate) = awaitCancellation()

        override fun tooSlow() {
            tooSlow++
        }
    }

    private object DirectTransactions : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    /** `fleet_drivers` in memory: the rows and their write times, with the same filters as the SQL. */
    private class InMemoryFleet(
        private val now: () -> Instant,
    ) : FleetRepository {
        private val rows = linkedMapOf<UUID, Pair<FleetDriver, Instant>>()

        /** How many times the tick read the changed rows. */
        @Volatile
        var reads = 0

        /** The next read throws CancellationException, as a cancelled query does while the tick loop is active. */
        @Volatile
        var cancelNextRead = false

        fun write(driver: FleetDriver) {
            rows[driver.driverId] = driver to now()
        }

        override suspend fun page(
            filter: FleetFilter,
            onlineSince: Instant,
            after: FleetCursor?,
            limit: Int,
        ): List<FleetDriver> = matching(filter, onlineSince).take(limit)

        override suspend fun changedSince(
            since: Instant,
            staleFrom: Instant,
            staleTo: Instant,
        ): List<FleetDriver> {
            reads++
            if (cancelNextRead) {
                cancelNextRead = false
                throw CancellationException("query cancelled")
            }
            return rows.values
                .filter { (driver, updatedAt) -> updatedAt > since || driver.lastSeenAt?.let { it in staleFrom..staleTo } == true }
                .map { it.first }
        }

        override suspend fun cells(
            filter: FleetFilter,
            onlineSince: Instant,
            cellDeg: Double,
        ): List<MapCell> =
            matching(filter, onlineSince)
                .groupBy { Math.floorDiv((it.position!!.lat / cellDeg).toLong(), 1L) to (it.position!!.lng / cellDeg).toLong() }
                .values
                .map { cell -> MapCell(cell.map { it.position!!.lat }.average(), cell.map { it.position!!.lng }.average(), cell.size) }

        private fun matching(
            filter: FleetFilter,
            onlineSince: Instant,
        ): List<FleetDriver> {
            val view = MapView(filter.bbox!!, filter.online, filter.onTrip)
            return rows.values.map { it.first }.filter { view.markerOf(it, onlineSince + 60.seconds, 60.seconds) != null }
        }

        override suspend fun find(driverId: UUID) = error("unused")

        override suspend fun update(
            driverId: UUID,
            updatedAt: Instant,
            change: (FleetDriver) -> FleetDriver?,
        ) = error("unused")

        override suspend fun savePositions(
            positions: Map<UUID, DriverPosition>,
            updatedAt: Instant,
        ) = error("unused")

        override suspend fun saveDevices(
            devices: Map<UUID, DeviceState>,
            updatedAt: Instant,
        ) = error("unused")

        override suspend fun counts(onlineSince: Instant): FleetCounts = error("unused")

        override suspend fun clear() = error("unused")

        override suspend fun insertAll(
            drivers: Collection<FleetDriver>,
            updatedAt: Instant,
        ) = error("unused")
    }
}
