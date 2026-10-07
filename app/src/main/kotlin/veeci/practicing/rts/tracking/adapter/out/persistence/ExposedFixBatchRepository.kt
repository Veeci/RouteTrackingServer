package veeci.practicing.rts.tracking.adapter.out.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.Meters
import veeci.practicing.rts.tracking.application.port.out.FixBatchRepository
import veeci.practicing.rts.tracking.application.port.out.InsertResult
import veeci.practicing.rts.tracking.application.port.out.NewBatch
import veeci.practicing.rts.tracking.application.port.out.StoredBatch
import veeci.practicing.rts.tracking.domain.AcceptedFix
import veeci.practicing.rts.tracking.domain.LocationFix
import veeci.practicing.rts.tracking.domain.LocationProvider
import veeci.practicing.rts.tracking.domain.Rejection
import veeci.practicing.rts.tracking.domain.RejectionReason
import veeci.practicing.rts.tracking.domain.SatelliteHealth
import veeci.practicing.rts.tracking.domain.SessionId
import kotlin.time.Instant

/** Runs inside the caller's transaction (see TransactionRunner); it opens none of its own. */
class ExposedFixBatchRepository : FixBatchRepository {
    override suspend fun find(
        sessionId: SessionId,
        seq: Long,
    ): StoredBatch? =
        FixBatchesTable
            .selectAll()
            .where { (FixBatchesTable.sessionId eq sessionId.value) and (FixBatchesTable.seq eq seq) }
            .singleOrNull()
            ?.toStoredBatch()

    override suspend fun highestSeq(sessionId: SessionId): Long? {
        val highest = FixBatchesTable.seq.max()
        return FixBatchesTable
            .select(highest)
            .where { FixBatchesTable.sessionId eq sessionId.value }
            .single()[highest]
    }

    override suspend fun lastAcceptedAtOrBefore(
        sessionId: SessionId,
        at: Instant,
    ): AcceptedFix? =
        FixesTable
            .selectAll()
            .where { (FixesTable.sessionId eq sessionId.value) and (FixesTable.recordedAt lessEq at) }
            .orderBy(FixesTable.recordedAt to SortOrder.DESC, FixesTable.seq to SortOrder.DESC, FixesTable.idx to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toAcceptedFix()

    override suspend fun insert(batch: NewBatch): InsertResult {
        // INSERT ... ON CONFLICT DO NOTHING. If another transaction holds the same key, Postgres waits for it
        // to finish; if it committed, this insert writes nothing and reports 0 rows.
        val inserted =
            FixBatchesTable
                .insertIgnore {
                    it[sessionId] = batch.sessionId.value
                    it[seq] = batch.seq
                    it[receivedAt] = batch.receivedAt
                    it[acceptedCount] = batch.accepted.size
                    it[rejections] = batch.rejected.map { rejection -> RejectionRow(rejection.index, rejection.reason.name) }
                }.insertedCount
        if (inserted == 0) {
            val existing = checkNotNull(find(batch.sessionId, batch.seq)) { "The conflicting batch must exist" }
            return InsertResult.AlreadyExists(existing)
        }
        // One statement for all fixes of the batch, instead of one round trip per fix.
        FixesTable.batchInsert(batch.accepted, shouldReturnGeneratedValues = false) { accepted ->
            val fix = accepted.fix
            this[FixesTable.sessionId] = batch.sessionId.value
            this[FixesTable.seq] = batch.seq
            this[FixesTable.idx] = accepted.index
            this[FixesTable.lat] = fix.point.lat
            this[FixesTable.lng] = fix.point.lng
            this[FixesTable.accuracyM] = fix.accuracy.value.toFloat()
            this[FixesTable.speedMps] = fix.speedMps?.toFloat()
            this[FixesTable.speedAccuracyMps] = fix.speedAccuracyMps?.toFloat()
            this[FixesTable.bearingDeg] = fix.bearingDeg?.toFloat()
            this[FixesTable.bearingAccuracyDeg] = fix.bearingAccuracyDeg?.toFloat()
            this[FixesTable.altitudeM] = fix.altitudeM?.toFloat()
            this[FixesTable.verticalAccuracyM] = fix.verticalAccuracyM?.toFloat()
            this[FixesTable.recordedAt] = fix.recordedAt
            this[FixesTable.provider] = fix.provider.name
            this[FixesTable.mock] = fix.mock
            this[FixesTable.satUsed] = fix.satellites?.usedInFix?.toShort()
            this[FixesTable.satMeanCn0DbHz] = fix.satellites?.meanCn0DbHz?.toFloat()
            this[FixesTable.distanceFromPrevM] = accepted.distanceFromPrev.value
            this[FixesTable.cumulativeM] = accepted.cumulative.value
            this[FixesTable.derivedSpeedMps] = accepted.derivedSpeedMps?.toFloat()
        }
        return InsertResult.Inserted
    }

    private fun ResultRow.toStoredBatch() =
        StoredBatch(
            sessionId = SessionId(this[FixBatchesTable.sessionId]),
            seq = this[FixBatchesTable.seq],
            receivedAt = this[FixBatchesTable.receivedAt],
            acceptedCount = this[FixBatchesTable.acceptedCount],
            rejected = this[FixBatchesTable.rejections].map { Rejection(it.index, RejectionReason.valueOf(it.reason)) },
        )

    private fun ResultRow.toAcceptedFix(): AcceptedFix {
        val satUsed = this[FixesTable.satUsed]
        val fix =
            LocationFix(
                point = GeoPoint(this[FixesTable.lat], this[FixesTable.lng]),
                accuracy = Meters(this[FixesTable.accuracyM].toDouble()),
                speedMps = this[FixesTable.speedMps]?.toDouble(),
                speedAccuracyMps = this[FixesTable.speedAccuracyMps]?.toDouble(),
                bearingDeg = this[FixesTable.bearingDeg]?.toDouble(),
                bearingAccuracyDeg = this[FixesTable.bearingAccuracyDeg]?.toDouble(),
                altitudeM = this[FixesTable.altitudeM]?.toDouble(),
                verticalAccuracyM = this[FixesTable.verticalAccuracyM]?.toDouble(),
                recordedAt = this[FixesTable.recordedAt],
                provider = LocationProvider.valueOf(this[FixesTable.provider]),
                mock = this[FixesTable.mock],
                satellites = satUsed?.let { SatelliteHealth(it.toInt(), this[FixesTable.satMeanCn0DbHz]?.toDouble()) },
            )
        return AcceptedFix(
            fix = fix,
            index = this[FixesTable.idx],
            distanceFromPrev = Meters(this[FixesTable.distanceFromPrevM]),
            cumulative = Meters(this[FixesTable.cumulativeM]),
            derivedSpeedMps = this[FixesTable.derivedSpeedMps]?.toDouble(),
        )
    }
}
