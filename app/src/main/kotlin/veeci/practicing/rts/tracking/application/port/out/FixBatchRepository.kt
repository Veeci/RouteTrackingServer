package veeci.practicing.rts.tracking.application.port.out

import veeci.practicing.rts.tracking.domain.AcceptedFix
import veeci.practicing.rts.tracking.domain.Rejection
import veeci.practicing.rts.tracking.domain.SessionId
import kotlin.time.Instant

/** Stored batches and their accepted fixes. A batch is identified by `(sessionId, seq)` and is never changed. */
interface FixBatchRepository {
    suspend fun find(
        sessionId: SessionId,
        seq: Long,
    ): StoredBatch?

    suspend fun highestSeq(sessionId: SessionId): Long?

    /** The latest accepted fix of the session recorded at or before [at]. */
    suspend fun lastAcceptedAtOrBefore(
        sessionId: SessionId,
        at: Instant,
    ): AcceptedFix?

    /** Stores the batch and its accepted fixes, unless a batch with the same `(sessionId, seq)` exists. */
    suspend fun insert(batch: NewBatch): InsertResult
}

data class NewBatch(
    val sessionId: SessionId,
    val seq: Long,
    val receivedAt: Instant,
    val accepted: List<AcceptedFix>,
    val rejected: List<Rejection>,
)

/** What is kept of a batch to answer a resend with the original counts. */
data class StoredBatch(
    val sessionId: SessionId,
    val seq: Long,
    val receivedAt: Instant,
    val acceptedCount: Int,
    val rejected: List<Rejection>,
)

sealed interface InsertResult {
    data object Inserted : InsertResult

    /** Another insert of the same `(sessionId, seq)` won. Nothing was written. */
    data class AlreadyExists(
        val existing: StoredBatch,
    ) : InsertResult
}
