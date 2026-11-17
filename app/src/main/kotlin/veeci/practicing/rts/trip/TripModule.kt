package veeci.practicing.rts.trip

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import veeci.practicing.rts.platform.config.EventsConfig
import veeci.practicing.rts.platform.config.RetentionConfig
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.platform.jobs.BackgroundWorker
import veeci.practicing.rts.platform.jobs.ScheduledJob
import veeci.practicing.rts.trip.adapter.inbound.event.ArrivalListener
import veeci.practicing.rts.trip.adapter.inbound.event.BatteryListener
import veeci.practicing.rts.trip.adapter.inbound.event.DriverCancelListener
import veeci.practicing.rts.trip.adapter.inbound.event.DriverRouteListener
import veeci.practicing.rts.trip.adapter.inbound.event.FixesAcceptedListener
import veeci.practicing.rts.trip.adapter.inbound.event.TripFinishedListener
import veeci.practicing.rts.trip.adapter.inbound.event.TripSummaryListener
import veeci.practicing.rts.trip.adapter.inbound.job.StaleDriverJob
import veeci.practicing.rts.trip.adapter.out.driver.DriverCancelPush
import veeci.practicing.rts.trip.adapter.out.driver.DriverRoutePush
import veeci.practicing.rts.trip.adapter.out.live.LiveProgressPublisher
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedProgressStateStore
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedRuleStateStore
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripEventOutbox
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripMomentRepository
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRepository
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRouteRepository
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripSummaryRepository
import veeci.practicing.rts.trip.application.RerouteHandler
import veeci.practicing.rts.trip.application.RerouteWorker
import veeci.practicing.rts.trip.application.Rerouter
import veeci.practicing.rts.trip.application.TripAdminService
import veeci.practicing.rts.trip.application.TripEventService
import veeci.practicing.rts.trip.application.TripHistoryService
import veeci.practicing.rts.trip.application.TripProgressService
import veeci.practicing.rts.trip.application.TripRouteService
import veeci.practicing.rts.trip.application.TripService
import veeci.practicing.rts.trip.application.TripSummaryService
import veeci.practicing.rts.trip.application.TripTrackService
import veeci.practicing.rts.trip.application.api.DriverTrips
import veeci.practicing.rts.trip.application.api.TripQuery
import veeci.practicing.rts.trip.application.port.out.ProgressPublisher
import veeci.practicing.rts.trip.application.port.out.ProgressStateStore
import veeci.practicing.rts.trip.application.port.out.RuleStateStore
import veeci.practicing.rts.trip.application.port.out.TripEventOutbox
import veeci.practicing.rts.trip.application.port.out.TripMomentRepository
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.application.port.out.TripSummaryRepository
import veeci.practicing.rts.trip.domain.PresenceRules
import veeci.practicing.rts.trip.domain.TripRules

/**
 * The trip context's object graph. TripService is also bound as TripQuery, the read API for other contexts;
 * the two listeners are found by the outbox relay.
 */
fun tripModule(
    events: EventsConfig,
    retention: RetentionConfig = RetentionConfig(),
): Module =
    module {
        single<TripRepository> { ExposedTripRepository() }
        single<TripEventOutbox> { ExposedTripEventOutbox(get()) }
        single<TripRouteRepository> { ExposedTripRouteRepository() }
        single { TripService(get(), get(), get(), get(), get(), get()) } bind TripQuery::class
        // Phase 11d: staff list every trip and cancel with a reason.
        single { TripAdminService(get(), get(), get(), get(), get()) }
        single { DriverCancelPush(get()) }
        single { DriverCancelListener(get()) } bind OutboxEventHandler::class

        single<ProgressStateStore> { ExposedProgressStateStore(get()) }
        single<ProgressPublisher> { LiveProgressPublisher(get()) }
        single { TripProgressService(get(), get(), get(), get(), get(), get()) }

        // Phase 7b: re-routes, planned by a worker outside the relay's transaction.
        single { TripRouteService(get(), get(), get(), get(), get(), get(), get()) } bind RerouteHandler::class
        single { RerouteWorker(get()) } binds arrayOf(Rerouter::class, BackgroundWorker::class)

        // Phase 7c: the driver's device gets the route it follows, live and after every connect.
        single(createdAtStart = true) { DriverRoutePush(get(), get()).also { it.start() } }
        single { DriverRouteListener(get()) } bind OutboxEventHandler::class
        single { FixesAcceptedListener(get()) } bind OutboxEventHandler::class
        single { TripFinishedListener(get()) } bind OutboxEventHandler::class

        // Phase 4: moments and auto-arrive, decided inside the relay's transaction.
        single<TripMomentRepository> { ExposedTripMomentRepository() }
        single<RuleStateStore> { ExposedRuleStateStore() }
        single {
            TripEventService(get(), get(), get(), get(), get(), get(), get(), TripRules(presence = PresenceRules(events.staleAfter)))
        }
        single { StaleDriverJob(get(), events.staleCheckEvery) } bind ScheduledJob::class
        single { ArrivalListener(get()) } bind OutboxEventHandler::class
        single { BatteryListener(get()) } bind OutboxEventHandler::class

        // Phase 8a: a summary of every completed trip.
        single<TripSummaryRepository> { ExposedTripSummaryRepository() }
        single { TripSummaryService(get(), get(), get(), get(), get(), get(), get(), retention.rawLocation, get()) }
        single { TripSummaryListener(get()) } bind OutboxEventHandler::class
        single { TripHistoryService(get(), get(), get()) } bind DriverTrips::class

        // Phase 8c: the track and timeline of ended trips.
        single { TripTrackService(get(), get(), get(), get(), get(), get(), retention.rawLocation) }
    }
