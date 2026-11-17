package veeci.practicing.rts.tracking.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.tracking.application.api.SessionMark
import veeci.practicing.rts.tracking.application.api.SessionTelemetry
import veeci.practicing.rts.tracking.application.api.TelemetryDevice
import veeci.practicing.rts.tracking.application.api.TelemetryFix
import veeci.practicing.rts.tracking.application.api.TelemetrySample
import veeci.practicing.rts.tracking.application.api.TelemetrySession
import veeci.practicing.rts.tracking.application.port.out.DeviceStatusSampleRepository
import veeci.practicing.rts.tracking.application.port.out.SessionTelemetryRepository
import veeci.practicing.rts.tracking.application.port.out.TrackingSessionRepository
import veeci.practicing.rts.tracking.domain.SessionId
import java.util.UUID
import kotlin.time.Instant

class TrackingTelemetry(
    private val sessions: TrackingSessionRepository,
    private val samples: DeviceStatusSampleRepository,
    private val telemetry: SessionTelemetryRepository,
    private val transactions: TransactionRunner,
) : SessionTelemetry {
    override suspend fun session(id: UUID): TelemetrySession? =
        transactions.inTransaction { sessions.find(SessionId(id)) }?.let { session ->
            TelemetrySession(
                session.id.value,
                session.driverId?.value,
                session.startedAt,
                session.lastSeenAt,
                session.device?.let { TelemetryDevice(it.manufacturer, it.model, it.androidSdk, it.rom, it.hasGms) },
            )
        }

    override suspend fun idleSessions(
        after: SessionMark?,
        lastSeenBefore: Instant,
        limit: Int,
    ): List<SessionMark> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return transactions.inTransaction { telemetry.idleSessions(after, lastSeenBefore, limit) }
    }

    override suspend fun fixes(id: UUID): List<TelemetryFix> = transactions.inTransaction { telemetry.fixes(SessionId(id)) }

    override suspend fun rejections(id: UUID): Map<String, Int> =
        transactions
            .inTransaction { telemetry.rejectionReasons(SessionId(id)) }
            .flatten()
            .groupingBy { it }
            .eachCount()

    override suspend fun statusSamples(id: UUID): List<TelemetrySample> =
        transactions.inTransaction { samples.forSession(SessionId(id)) }.map {
            TelemetrySample(it.at, it.battery.levelPercent, it.battery.isCharging, it.motion)
        }
}
