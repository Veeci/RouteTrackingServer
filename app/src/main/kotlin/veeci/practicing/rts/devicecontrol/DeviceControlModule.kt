package veeci.practicing.rts.devicecontrol

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import veeci.practicing.rts.devicecontrol.adapter.inbound.event.CommandIssuedListener
import veeci.practicing.rts.devicecontrol.adapter.inbound.job.CommandExpiryJob
import veeci.practicing.rts.devicecontrol.adapter.out.persistence.ExposedCommandEventOutbox
import veeci.practicing.rts.devicecontrol.adapter.out.persistence.ExposedDeviceCommandRepository
import veeci.practicing.rts.devicecontrol.application.CommandDelivery
import veeci.practicing.rts.devicecontrol.application.CommandService
import veeci.practicing.rts.devicecontrol.application.port.out.CommandEventOutbox
import veeci.practicing.rts.devicecontrol.application.port.out.DeviceCommandRepository
import veeci.practicing.rts.platform.config.CommandsConfig
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.platform.jobs.ScheduledJob

/** The device control context's object graph (phase 5): commands, their delivery and their expiry job. */
fun deviceControlModule(config: CommandsConfig): Module =
    module {
        single<DeviceCommandRepository> { ExposedDeviceCommandRepository() }
        single<CommandEventOutbox> { ExposedCommandEventOutbox(get()) }
        single { CommandService(get(), get(), get(), get(), config.expireAfter) }
        single { CommandExpiryJob(get(), config.expiryCheckEvery) } bind ScheduledJob::class
        // Built at startup: it registers with the driver sockets before any driver connects.
        single(createdAtStart = true) { CommandDelivery(get(), get()).also { it.start() } }
        single { CommandIssuedListener(get()) } bind OutboxEventHandler::class
    }
