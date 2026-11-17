package veeci.practicing.rts.fleet.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import veeci.practicing.rts.fleet.application.port.out.FleetRepository
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.fleet.domain.FleetMarker
import veeci.practicing.rts.fleet.domain.MapCell
import veeci.practicing.rts.fleet.domain.MapView
import veeci.practicing.rts.fleet.domain.SentMarkers
import veeci.practicing.rts.platform.config.FleetConfig
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.jobs.BackgroundWorker
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/** One message for a live map subscriber; the socket turns it into a protocol message. */
sealed interface FleetUpdate {
    val at: Instant

    data class Snapshot(
        val markers: List<FleetMarker>,
        override val at: Instant,
    ) : FleetUpdate

    data class Delta(
        val upserts: List<FleetMarker>,
        val removes: List<UUID>,
        override val at: Instant,
    ) : FleetUpdate

    data class Clusters(
        val cells: List<MapCell>,
        val cellDeg: Double,
        override val at: Instant,
    ) : FleetUpdate {
        val total: Int get() = cells.sumOf { it.count }
    }
}

/** Where one subscriber's updates go: its socket. */
interface FleetSink {
    suspend fun send(update: FleetUpdate)

    /** A [send] took longer than [FleetConfig.sendTimeout]. The socket closes; the subscriber gets nothing more. */
    fun tooSlow()
}

/**
 * The live map (phase 11c). Since phase 10 the projection is written on whichever instance holds the relay lock, so
 * nothing here caches positions: the `fleet_drivers` table is the source.
 *
 * Each instance runs one tick every [FleetConfig.tick], however many sockets it serves. A tick reads, in one query,
 * the rows written since the oldest subscriber's last message (minus [OVERLAP]) and the drivers who just went
 * offline, then hands the same rows to every subscriber, which filters them in memory against its own area and its
 * [SentMarkers]. A subscriber still busy with its last message skips the tick; its next delta covers both, because
 * the query starts at that subscriber's own last message.
 */
class FleetLive(
    private val fleet: FleetRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val staleAfter: Duration,
    private val config: FleetConfig,
) : BackgroundWorker {
    private val log = LoggerFactory.getLogger(FleetLive::class.java)
    private val watches = ConcurrentHashMap.newKeySet<Watch>()

    /** Sends to subscribers. Not joined by the tick: a slow socket must not hold back the others. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("fleet-live"))

    @Volatile
    private var loop: Job? = null

    /** A new subscriber. It gets nothing until [Watch.subscribe]; [Watch.close] it when the socket ends. */
    fun open(sink: FleetSink): Watch = Watch(sink).also { watches += it }

    override fun start(scope: CoroutineScope) {
        loop =
            scope.launch(CoroutineName("fleet-tick")) {
                while (isActive) {
                    delay(config.tick)
                    tickSafely()
                }
            }
    }

    override suspend fun stop() {
        loop?.cancelAndJoin()
        work.cancel()
    }

    /**
     * Reads the changed rows once and starts every subscriber's delta. Returns the started sends, so a test can wait for
     * them; the loop does not.
     */
    internal suspend fun tick(): List<Job> {
        val now = now()
        val reading = watches.filter { it.wantsRows }
        val rows =
            if (reading.isEmpty()) {
                emptyList()
            } else {
                // Every subscriber's last message is newer than this; the overlap catches rows whose transaction
                // committed after the time it wrote into updated_at.
                val since = reading.minOf { it.since } - OVERLAP
                transactions.inTransaction { fleet.changedSince(since, since - staleAfter, now - staleAfter) }
            }
        return watches.map { watch -> work.launch { watch.onTick(rows, now) } }
    }

    @Suppress("TooGenericExceptionCaught") // a failed tick (database down) is logged; the next one runs on time
    private suspend fun tickSafely() {
        try {
            tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Fleet tick failed; the next one runs in {}", config.tick, e)
        }
    }

    private fun now(): Instant = clock.instant().toKotlinInstant()

    /**
     * One subscriber: its area, the markers it has, and the time of its last message. The mutex keeps a subscribe and
     * a tick from sending at once, so the client sees a snapshot, then deltas, in order.
     */
    inner class Watch internal constructor(
        private val sink: FleetSink,
    ) {
        private val mutex = Mutex()
        private val sent = SentMarkers()

        @Volatile
        private var view: MapView? = null

        @Volatile
        private var clustered = false

        /** The time of the state the client has; the tick reads the rows changed after it. */
        @Volatile
        internal var since: Instant = Instant.DISTANT_PAST
            private set

        /** Has a snapshot and gets deltas. A subscriber still taking its first snapshot has no `since` to read from. */
        internal val wantsRows: Boolean get() = view != null && !clustered && since != Instant.DISTANT_PAST

        /** Replaces the area and sends a fresh snapshot, or clusters when the area holds too many drivers. */
        suspend fun subscribe(view: MapView) {
            mutex.withLock {
                this.view = view
                show(view)
            }
        }

        fun close() {
            watches -= this
        }

        @Suppress("TooGenericExceptionCaught") // one subscriber's failure (its socket closed) never stops the others
        internal suspend fun onTick(
            rows: List<FleetDriver>,
            at: Instant,
        ) {
            // Still sending the last message: skip this tick. The next one reads from this subscriber's own `since`.
            if (!mutex.tryLock()) return
            try {
                val view = view
                // A snapshot taken after these rows were read is newer than they are: skip them too.
                if (view != null && since <= at) {
                    if (clustered) clusterTick(view) else delta(view, rows, at)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("Fleet update not sent", e)
            } finally {
                mutex.unlock()
            }
        }

        private suspend fun delta(
            view: MapView,
            rows: List<FleetDriver>,
            at: Instant,
        ) {
            val changes = sent.apply(rows, view, at, staleAfter)
            if (sent.size > config.maxMarkers) {
                sendClusters(view)
            } else if (deliver(FleetUpdate.Delta(changes.upserts, changes.removes, at))) {
                since = at
            }
        }

        /** A snapshot, or clusters when there are more than [FleetConfig.maxMarkers] drivers to show. */
        private suspend fun show(view: MapView) {
            val now = now()
            val drivers = transactions.inTransaction { fleet.page(view.filter, now - staleAfter, null, config.maxMarkers + 1) }
            if (drivers.size > config.maxMarkers) {
                sendClusters(view)
            } else {
                sent.reset(drivers.mapNotNull { view.markerOf(it, now, staleAfter) })
                clustered = false
                if (deliver(FleetUpdate.Snapshot(sent.markers, now))) since = now
            }
        }

        /** In cluster mode, every tick: fresh counts, or a snapshot and deltas again once the area holds few enough. */
        private suspend fun clusterTick(view: MapView) {
            val clusters = readClusters(view)
            if (clusters.total <= config.maxMarkers) show(view) else sendClusters(view, clusters)
        }

        private suspend fun sendClusters(
            view: MapView,
            clusters: FleetUpdate.Clusters? = null,
        ) {
            val update = clusters ?: readClusters(view)
            sent.clear()
            clustered = true
            if (deliver(update)) since = update.at
        }

        private suspend fun readClusters(view: MapView): FleetUpdate.Clusters {
            val now = now()
            val cells = transactions.inTransaction { fleet.cells(view.filter, now - staleAfter, view.cellDeg) }
            return FleetUpdate.Clusters(cells, view.cellDeg, now)
        }

        /** Sends within the timeout, or tells the sink it is too slow; false then. */
        private suspend fun deliver(update: FleetUpdate): Boolean {
            val sentInTime = withTimeoutOrNull(config.sendTimeout) { sink.send(update) } != null
            if (!sentInTime) {
                close()
                sink.tooSlow()
            }
            return sentInTime
        }
    }

    companion object {
        /** How far back each tick reads beyond the last message: longer than a projection transaction takes. */
        val OVERLAP = 5.seconds
    }
}
