package veeci.practicing.rts.tracking

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import veeci.practicing.rts.platform.config.RetentionConfig
import veeci.practicing.rts.platform.config.TrackingConfig
import veeci.practicing.rts.platform.config.WsConfig
import veeci.practicing.rts.platform.jobs.ScheduledJob
import veeci.practicing.rts.protocol.LimitsDto
import veeci.practicing.rts.shared.geo.Meters
import veeci.practicing.rts.tracking.adapter.inbound.job.RetentionJob
import veeci.practicing.rts.tracking.adapter.inbound.ws.ConnectedDrivers
import veeci.practicing.rts.tracking.adapter.inbound.ws.DriverSocket
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedDeviceStatusSampleRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedFixBatchRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedRetentionRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedSessionTelemetryRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackHistoryRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingEventOutbox
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingSessionRepository
import veeci.practicing.rts.tracking.adapter.out.redis.RedisDriverLink
import veeci.practicing.rts.tracking.application.LocationRetention
import veeci.practicing.rts.tracking.application.TrackingHistory
import veeci.practicing.rts.tracking.application.TrackingPresence
import veeci.practicing.rts.tracking.application.TrackingService
import veeci.practicing.rts.tracking.application.TrackingTelemetry
import veeci.practicing.rts.tracking.application.api.DriverLink
import veeci.practicing.rts.tracking.application.api.DriverPresence
import veeci.practicing.rts.tracking.application.api.DriverTrack
import veeci.practicing.rts.tracking.application.api.SessionTelemetry
import veeci.practicing.rts.tracking.application.port.out.DeviceStatusSampleRepository
import veeci.practicing.rts.tracking.application.port.out.FixBatchRepository
import veeci.practicing.rts.tracking.application.port.out.RetentionRepository
import veeci.practicing.rts.tracking.application.port.out.TrackingEventOutbox
import veeci.practicing.rts.tracking.application.port.out.TrackingSessionRepository
import veeci.practicing.rts.tracking.domain.FixPipeline
import veeci.practicing.rts.tracking.domain.PipelineConfig

/** The tracking context's object graph: ports bound to their Postgres adapters, the service and the driver socket. */
fun trackingModule(
    config: TrackingConfig,
    ws: WsConfig,
    redis: Boolean = false,
    retention: RetentionConfig = RetentionConfig(),
): Module =
    module {
        single<TrackingSessionRepository> { ExposedTrackingSessionRepository() }
        single<FixBatchRepository> { ExposedFixBatchRepository() }
        single<TrackingEventOutbox> { ExposedTrackingEventOutbox(get()) }
        single {
            FixPipeline(
                PipelineConfig(
                    maxAccuracy = Meters(config.maxAccuracyM),
                    maxClockSkew = config.maxClockSkew,
                    maxAge = config.maxAge,
                    maxSpeedMps = config.maxSpeedMps,
                    rejectMock = config.rejectMock,
                ),
            )
        }
        single<DeviceStatusSampleRepository> { ExposedDeviceStatusSampleRepository() }
        single { TrackingService(get(), get(), get(), get(), get(), get(), config.maxFixesPerBatch, get()) }
        // Presence, read by the trip context's stale-driver job.
        single<DriverPresence> { TrackingPresence(get(), get()) }
        // Where a driver drove, read by the trip context for summaries and history (phase 8).
        single<DriverTrack> { TrackingHistory(ExposedTrackHistoryRepository(), get()) }
        // Raw location data past retention is deleted (phase 10).
        single<RetentionRepository> { ExposedRetentionRepository() }
        single { LocationRetention(get(), get(), get(), retention) }
        single { RetentionJob(get(), get(), retention.checkEvery) } bind ScheduledJob::class
        // One session's raw telemetry, read by the telemetry context for session health (phase 9).
        single<SessionTelemetry> { TrackingTelemetry(get(), get(), ExposedSessionTelemetryRepository(), get()) }
        // The driver sockets of this instance; device control sends commands through it (phase 5).
        // With Redis, a message for a driver on another instance goes through Redis (phase 10).
        if (redis) {
            single { ConnectedDrivers() }
            single<DriverLink>(createdAtStart = true) { RedisDriverLink(get<ConnectedDrivers>(), get()) }
        } else {
            single { ConnectedDrivers() } bind DriverLink::class
        }
        single {
            val limits = LimitsDto(config.maxFixesPerBatch, ws.maxFrameBytes, ws.messagesPerSecond)
            DriverSocket(get(), get(), get(), limits, config.handshakeTimeout, get(), get(), get())
        }
    }
