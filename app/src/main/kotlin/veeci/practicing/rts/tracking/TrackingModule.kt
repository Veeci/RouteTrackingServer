package veeci.practicing.rts.tracking

import org.koin.core.module.Module
import org.koin.dsl.module
import veeci.practicing.rts.platform.config.TrackingConfig
import veeci.practicing.rts.platform.config.WsConfig
import veeci.practicing.rts.protocol.LimitsDto
import veeci.practicing.rts.shared.geo.Meters
import veeci.practicing.rts.tracking.adapter.inbound.ws.DriverSocket
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedFixBatchRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingEventOutbox
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingSessionRepository
import veeci.practicing.rts.tracking.application.TrackingService
import veeci.practicing.rts.tracking.application.port.out.FixBatchRepository
import veeci.practicing.rts.tracking.application.port.out.TrackingEventOutbox
import veeci.practicing.rts.tracking.application.port.out.TrackingSessionRepository
import veeci.practicing.rts.tracking.domain.FixPipeline
import veeci.practicing.rts.tracking.domain.PipelineConfig

/** The tracking context's object graph: ports bound to their Postgres adapters, the service and the driver socket. */
fun trackingModule(
    config: TrackingConfig,
    ws: WsConfig,
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
        single { TrackingService(get(), get(), get(), get(), get(), get(), config.maxFixesPerBatch) }
        single {
            val limits = LimitsDto(config.maxFixesPerBatch, ws.maxFrameBytes, ws.messagesPerSecond)
            DriverSocket(get(), get(), get(), limits, config.handshakeTimeout)
        }
    }
