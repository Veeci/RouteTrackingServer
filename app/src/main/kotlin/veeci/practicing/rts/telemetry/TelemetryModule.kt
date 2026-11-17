package veeci.practicing.rts.telemetry

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import veeci.practicing.rts.platform.config.TelemetryConfig
import veeci.practicing.rts.platform.jobs.ScheduledJob
import veeci.practicing.rts.telemetry.adapter.inbound.job.SessionHealthJob
import veeci.practicing.rts.telemetry.adapter.out.persistence.ExposedSessionHealthRepository
import veeci.practicing.rts.telemetry.application.SessionHealthService
import veeci.practicing.rts.telemetry.application.port.out.SessionHealthRepository

/** The telemetry context's object graph (phase 9): session health, its job and its storage. */
fun telemetryModule(config: TelemetryConfig): Module =
    module {
        single<SessionHealthRepository> { ExposedSessionHealthRepository() }
        single { SessionHealthService(get(), get(), get(), get(), get(), config) }
        single { SessionHealthJob(get(), config.computeEvery) } bind ScheduledJob::class
    }
