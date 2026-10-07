package veeci.practicing.rts.testing.tracking

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.tracking.application.port.out.FixBatchRepository
import veeci.practicing.rts.tracking.application.port.out.InsertResult
import veeci.practicing.rts.tracking.application.port.out.NewBatch
import veeci.practicing.rts.tracking.application.port.out.StoredBatch
import veeci.practicing.rts.tracking.application.port.out.TrackingEventOutbox
import veeci.practicing.rts.tracking.application.port.out.TrackingSessionRepository
import veeci.practicing.rts.tracking.domain.AcceptedFix
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.TrackingSession
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import kotlin.time.Instant

/*
 * In-memory versions of the tracking ports, for fast tests of the service and the socket.
 * They behave like the database versions in what the service relies on (T2.5 runs the same contract
 * tests against both), but they have no transactions: a failure does not undo earlier writes.
 */

/** Runs the block directly. There is nothing to commit or roll back in memory. */
object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

class InMemoryTrackingSessionRepository : TrackingSessionRepository {
    /** A stored copy, like a table row: changes to a loaded session are lost unless it is saved again. */
    private data class Row(
        val id: SessionId,
        val deviceId: DeviceId,
        val sdkVersion: String,
        val startedAt: Instant,
        val lastSeenAt: Instant,
    )

    private val mutex = Mutex()
    private val rows = mutableMapOf<SessionId, Row>()

    override suspend fun find(id: SessionId): TrackingSession? =
        mutex.withLock {
            rows[id]?.let { TrackingSession(it.id, it.deviceId, it.sdkVersion, it.startedAt, it.lastSeenAt) }
        }

    override suspend fun save(session: TrackingSession) =
        mutex.withLock {
            rows[session.id] = Row(session.id, session.deviceId, session.sdkVersion, session.startedAt, session.lastSeenAt)
        }
}

class InMemoryFixBatchRepository : FixBatchRepository {
    private val mutex = Mutex()
    private val batches = mutableMapOf<Pair<SessionId, Long>, StoredBatch>()
    private val fixes = mutableMapOf<SessionId, MutableList<AcceptedFix>>()

    /** Every stored batch, for assertions. */
    val stored: List<StoredBatch> get() = batches.values.toList()

    override suspend fun find(
        sessionId: SessionId,
        seq: Long,
    ): StoredBatch? = mutex.withLock { batches[sessionId to seq] }

    override suspend fun highestSeq(sessionId: SessionId): Long? =
        mutex.withLock { batches.keys.filter { it.first == sessionId }.maxOfOrNull { it.second } }

    override suspend fun lastAcceptedAtOrBefore(
        sessionId: SessionId,
        at: Instant,
    ): AcceptedFix? =
        mutex.withLock {
            fixes[sessionId].orEmpty().filter { it.fix.recordedAt <= at }.maxByOrNull { it.fix.recordedAt }
        }

    override suspend fun insert(batch: NewBatch): InsertResult =
        mutex.withLock {
            val key = batch.sessionId to batch.seq
            batches[key]?.let { return@withLock InsertResult.AlreadyExists(it) }
            batches[key] = StoredBatch(batch.sessionId, batch.seq, batch.receivedAt, batch.accepted.size, batch.rejected)
            fixes.getOrPut(batch.sessionId) { mutableListOf() } += batch.accepted
            InsertResult.Inserted
        }
}

class InMemoryTrackingEventOutbox : TrackingEventOutbox {
    private val mutex = Mutex()
    private val added = mutableListOf<FixesAccepted>()

    /** Every event added so far, in order. */
    val events: List<FixesAccepted> get() = added.toList()

    override suspend fun add(event: FixesAccepted) = mutex.withLock { added += event }
}
