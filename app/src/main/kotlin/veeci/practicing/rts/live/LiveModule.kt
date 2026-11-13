package veeci.practicing.rts.live

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import veeci.practicing.rts.live.adapter.inbound.event.TripMomentListener
import veeci.practicing.rts.live.adapter.inbound.event.TripRouteListener
import veeci.practicing.rts.live.adapter.inbound.event.TripStatusListener
import veeci.practicing.rts.live.adapter.inbound.ws.GuestSocket
import veeci.practicing.rts.live.adapter.out.bus.InMemoryLiveUpdateBus
import veeci.practicing.rts.live.application.api.LiveUpdates
import veeci.practicing.rts.live.application.port.out.LiveUpdateBus
import veeci.practicing.rts.platform.events.OutboxEventHandler

/** The live context's object graph: the bus (also the public LiveUpdates API), the status listener, the guest socket. */
fun liveModule(): Module =
    module {
        single { InMemoryLiveUpdateBus() } binds arrayOf(LiveUpdateBus::class, LiveUpdates::class)
        single { TripStatusListener(get(), get()) } bind OutboxEventHandler::class
        single { TripMomentListener(get(), get()) } bind OutboxEventHandler::class
        single { TripRouteListener(get()) } bind OutboxEventHandler::class
        single { GuestSocket(get(), get(), get(), get()) }
    }
