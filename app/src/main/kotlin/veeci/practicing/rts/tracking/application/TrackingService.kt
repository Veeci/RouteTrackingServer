package veeci.practicing.rts.tracking.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.tracking.application.port.out.FixBatchRepository
import veeci.practicing.rts.tracking.application.port.out.InsertResult
import veeci.practicing.rts.tracking.application.port.out.NewBatch
import veeci.practicing.rts.tracking.application.port.out.StoredBatch
import veeci.practicing.rts.tracking.application.port.out.TrackingEventOutbox
import veeci.practicing.rts.tracking.application.port.out.TrackingSessionRepository
import veeci.practicing.rts.tracking.domain.AcceptedFix
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.FixPipeline
import veeci.practicing.rts.tracking.domain.FixReading
import veeci.practicing.rts.tracking.domain.PipelineContext
import veeci.practicing.rts.tracking.domain.Rejection
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.TrackingError
import veeci.practicing.rts.tracking.domain.TrackingSession
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import java.time.Clock
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

data class OpenSession(
    val sessionId: SessionId,
    val deviceId: DeviceId,
    val sdkVersion: String,
)

data class SessionOpened(
    val sessionId: SessionId,
    /** The seq the client sends next. */
    val resumeFromSeq: Long,
)

data class IngestBatch(
    val sessionId: SessionId,
    val seq: Long,
    val fixes: List<FixReading>,
)

data class BatchResult(
    val seq: Long,
    val accepted: Int,
    val rejected: List<Rejection>,
    /** True if this seq was stored before; the counts are the original ones. */
    val duplicate: Boolean,
)

/**
 * The use cases of a driver session. Each method is one transaction, and it returns only after the commit:
 * that is what makes "ack only after the batch is stored" true for the caller.
 */
class TrackingService(
    private val sessions: TrackingSessionRepository,
    private val batches: FixBatchRepository,
    private val outbox: TrackingEventOutbox,
    private val transactions: TransactionRunner,
    private val pipeline: FixPipeline,
    private val clock: Clock,
    private val maxFixesPerBatch: Int,
) {
    /** Starts a session, or continues it after a reconnect. Says which seq the client sends next. */
    suspend fun openSession(command: OpenSession): SessionOpened =
        transactions.inTransaction {
            val now = now()
            val session =
                sessions
                    .find(command.sessionId)
                    ?.apply { reconnect(command.deviceId, command.sdkVersion, now) }
                    ?: TrackingSession(command.sessionId, command.deviceId, command.sdkVersion, startedAt = now)
            sessions.save(session)
            SessionOpened(session.id, session.resumeFrom(batches.highestSeq(session.id)))
        }

    /** Runs the batch through the pipeline and stores it. A seq that is already stored is answered, not stored again. */
    suspend fun ingest(command: IngestBatch): BatchResult {
        shapeError(command)?.let { throw it }
        return transactions.inTransaction {
            batches.find(command.sessionId, command.seq)?.toResult()
                ?: store(command)
        }
    }

    /** Problems that a batch has on its own, before anything is read from the database. */
    private fun shapeError(command: IngestBatch): DomainException? =
        when {
            command.seq < 1 -> {
                DomainException(TrackingError.INVALID_SEQ, "seq starts at 1")
            }

            command.fixes.isEmpty() -> {
                DomainException(TrackingError.EMPTY_BATCH, "A batch needs at least one fix")
            }

            command.fixes.size > maxFixesPerBatch -> {
                DomainException(TrackingError.BATCH_TOO_LARGE, "A batch holds at most $maxFixesPerBatch fixes")
            }

            else -> {
                null
            }
        }

    private suspend fun store(command: IngestBatch): BatchResult {
        val session =
            sessions.find(command.sessionId)
                ?: throw DomainException(TrackingError.SESSION_NOT_FOUND, "Send hello before the first batch")
        val now = now()
        val previous = batches.lastAcceptedAtOrBefore(session.id, command.fixes.minOf { it.recordedAt })
        val result = pipeline.process(command.fixes, PipelineContext(now, previous))
        val batch = NewBatch(session.id, command.seq, receivedAt = now, result.accepted, result.rejected)
        return when (val insert = batches.insert(batch)) {
            // A concurrent request stored the same seq first: answer like a resend.
            is InsertResult.AlreadyExists -> {
                insert.existing.toResult()
            }

            InsertResult.Inserted -> {
                session.touch(now)
                sessions.save(session)
                if (result.accepted.isNotEmpty()) outbox.add(fixesAccepted(session, command.seq, result.accepted))
                BatchResult(command.seq, result.accepted.size, result.rejected, duplicate = false)
            }
        }
    }

    private fun now(): Instant = clock.instant().toKotlinInstant()

    private fun StoredBatch.toResult() = BatchResult(seq, acceptedCount, rejected, duplicate = true)

    private fun fixesAccepted(
        session: TrackingSession,
        seq: Long,
        accepted: List<AcceptedFix>,
    ) = FixesAccepted(
        sessionId = session.id.value,
        deviceId = session.deviceId.value,
        seq = seq,
        positions =
            accepted.map {
                FixesAccepted.Position(it.fix.point, it.fix.recordedAt, it.fix.speedMps, it.fix.bearingDeg, it.cumulative.value)
            },
    )
}
