package veeci.practicing.rts.trip

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.trip.adapter.inbound.event.FixesAcceptedListener
import veeci.practicing.rts.trip.adapter.inbound.event.TripFinishedListener
import veeci.practicing.rts.trip.adapter.out.live.LiveProgressPublisher
import veeci.practicing.rts.trip.adapter.out.memory.InMemoryProgressStateStore
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripEventOutbox
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRepository
import veeci.practicing.rts.trip.application.TripProgressService
import veeci.practicing.rts.trip.application.TripService
import veeci.practicing.rts.trip.application.api.TripQuery
import veeci.practicing.rts.trip.application.port.out.ProgressPublisher
import veeci.practicing.rts.trip.application.port.out.ProgressStateStore
import veeci.practicing.rts.trip.application.port.out.TripEventOutbox
import veeci.practicing.rts.trip.application.port.out.TripRepository

/**
 * The trip context's object graph. TripService is also bound as TripQuery, the read API for other contexts;
 * the two listeners are found by the outbox relay.
 */
fun tripModule(): Module =
    module {
        single<TripRepository> { ExposedTripRepository() }
        single<TripEventOutbox> { ExposedTripEventOutbox(get()) }
        single { TripService(get(), get(), get(), get()) } bind TripQuery::class

        single<ProgressStateStore> { InMemoryProgressStateStore() }
        single<ProgressPublisher> { LiveProgressPublisher(get()) }
        single { TripProgressService(get(), get(), get(), get()) }
        single { FixesAcceptedListener(get()) } bind OutboxEventHandler::class
        single { TripFinishedListener(get()) } bind OutboxEventHandler::class
    }
