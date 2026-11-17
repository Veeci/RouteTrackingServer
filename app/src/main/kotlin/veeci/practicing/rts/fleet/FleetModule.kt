package veeci.practicing.rts.fleet

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import veeci.practicing.rts.fleet.adapter.inbound.event.FleetAccountListener
import veeci.practicing.rts.fleet.adapter.inbound.event.FleetDeviceStatusListener
import veeci.practicing.rts.fleet.adapter.inbound.event.FleetPositionListener
import veeci.practicing.rts.fleet.adapter.inbound.event.FleetTripListener
import veeci.practicing.rts.fleet.adapter.inbound.ws.FleetSocket
import veeci.practicing.rts.fleet.adapter.out.persistence.ExposedFleetRepository
import veeci.practicing.rts.fleet.application.FleetLive
import veeci.practicing.rts.fleet.application.FleetProjection
import veeci.practicing.rts.fleet.application.FleetRebuild
import veeci.practicing.rts.fleet.application.FleetService
import veeci.practicing.rts.fleet.application.port.out.FleetRepository
import veeci.practicing.rts.platform.config.EventsConfig
import veeci.practicing.rts.platform.config.FleetConfig
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.platform.jobs.BackgroundWorker

/**
 * The fleet context's object graph (phase 11): a read model of every driver, kept by four event handlers and read by
 * the admin API and the live fleet socket. It reads other contexts only through their public APIs.
 */
fun fleetModule(
    events: EventsConfig,
    live: FleetConfig,
): Module =
    module {
        single<FleetRepository> { ExposedFleetRepository() }
        single { FleetProjection(get(), get(), get()) }
        single { FleetPositionListener(get()) } bind OutboxEventHandler::class
        single { FleetDeviceStatusListener(get()) } bind OutboxEventHandler::class
        single { FleetTripListener(get()) } bind OutboxEventHandler::class
        single { FleetAccountListener(get()) } bind OutboxEventHandler::class
        // "Online" uses the same stale time that tells guests a driver is gone.
        single { FleetService(get(), get(), get(), get(), get(), get(), get(), events.staleAfter) }
        single { FleetRebuild(get(), get(), get(), get(), get(), get(), get()) }
        // One tick per instance for every fleet socket it serves (11c).
        single { FleetLive(get(), get(), get(), events.staleAfter, live) } bind BackgroundWorker::class
        single { FleetSocket(get(), get()) }
    }
