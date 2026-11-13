package veeci.practicing.rts

import com.sksamuel.hoplite.ConfigException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.KoinIsolated
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory
import veeci.practicing.rts.devicecontrol.adapter.inbound.web.adminCommandRoutes
import veeci.practicing.rts.devicecontrol.deviceControlModule
import veeci.practicing.rts.identity.adapter.inbound.web.devTokenRoutes
import veeci.practicing.rts.identity.identityModule
import veeci.practicing.rts.live.adapter.inbound.ws.guestSocket
import veeci.practicing.rts.live.liveModule
import veeci.practicing.rts.platform.config.AppConfig
import veeci.practicing.rts.platform.config.AppConfigLoader
import veeci.practicing.rts.platform.config.AppEnv
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.di.platformModule
import veeci.practicing.rts.platform.events.OutboxRelay
import veeci.practicing.rts.platform.http.configureErrorHandling
import veeci.practicing.rts.platform.http.configureRequestGuards
import veeci.practicing.rts.platform.http.configureSerialization
import veeci.practicing.rts.platform.jobs.JobScheduler
import veeci.practicing.rts.platform.jobs.ScheduledJob
import veeci.practicing.rts.platform.lifecycle.configureGracefulShutdown
import veeci.practicing.rts.platform.observability.configureMetrics
import veeci.practicing.rts.platform.observability.configureRequestLogging
import veeci.practicing.rts.platform.observability.healthRoutes
import veeci.practicing.rts.platform.security.AUTH_JWT
import veeci.practicing.rts.platform.security.configureAuthentication
import veeci.practicing.rts.platform.ws.configureWebSockets
import veeci.practicing.rts.platform.ws.echoEndpoint
import veeci.practicing.rts.routing.adapter.inbound.web.routeRoutes
import veeci.practicing.rts.routing.application.RoadGraphLoader
import veeci.practicing.rts.routing.routingModule
import veeci.practicing.rts.tracking.adapter.inbound.ws.driverSocket
import veeci.practicing.rts.tracking.trackingModule
import veeci.practicing.rts.trip.adapter.inbound.web.tripRoutes
import veeci.practicing.rts.trip.tripModule
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("veeci.practicing.rts.Application")

fun main(args: Array<String>) {
    val config =
        try {
            AppConfigLoader.load()
        } catch (e: ConfigException) {
            log.error("Invalid configuration, refusing to start:\n{}", e.message)
            exitProcess(1)
        }
    log.info("Starting with {}", config)

    embeddedServer(
        Netty,
        configure = {
            connector { port = config.http.port }
            shutdownGracePeriod = config.http.shutdownGracePeriod.inWholeMilliseconds
            shutdownTimeout = config.http.shutdownTimeout.inWholeMilliseconds
        },
    ) { module(config) }.start(wait = true)
}

/** Installs every plugin and route. Tests call this directly with their own [AppConfig]. */
fun Application.module(
    config: AppConfig,
    overrides: List<Module> = emptyList(),
) {
    // One registry for the whole process: HTTP timers, JVM, pool and (later) business metrics all land here.
    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(KoinIsolated) {
        slf4jLogger()
        modules(listOf(appModule(config, metrics)) + overrides)
    }
    if (config.db.migrateOnStart) Migrations.run(get())
    configureGracefulShutdown(config.http)
    // Delivers stored events to the contexts that react to them; stops with the application.
    get<OutboxRelay>().start(this)
    // Periodic jobs (the stale-driver check); each run happens on one instance only.
    get<JobScheduler>().start(this, getKoin().getAll<ScheduledJob>())
    // The road graph loads in the background; readiness reports `graph` DOWN until it is ready.
    get<RoadGraphLoader>().start(this)

    configureRequestLogging()
    configureMetrics(metrics)
    configureSerialization()
    configureRequestGuards(config.http)
    configureErrorHandling()
    configureAuthentication(get())
    configureWebSockets(config.ws)

    routing {
        healthRoutes(get())
        // Tokens without a password: only where no real user data exists.
        if (config.app.env in setOf(AppEnv.DEV, AppEnv.TEST)) devTokenRoutes(get())
        authenticate(AUTH_JWT) {
            driverSocket(get())
            guestSocket(get())
            tripRoutes(get(), get())
            routeRoutes(get())
            adminCommandRoutes(get())
        }
        if (config.app.env != AppEnv.PROD) echoEndpoint(get())
        // Interactive API docs generated from the contract; local development only.
        if (config.app.env == AppEnv.DEV) swaggerUI(path = "docs", swaggerFile = "openapi/rts-v1.yaml")
    }
}

/** The whole object graph: the platform plus every bounded context. Production and the wiring test both use it. */
fun appModule(
    config: AppConfig,
    metrics: MeterRegistry,
): Module =
    module {
        includes(
            platformModule(config, metrics),
            identityModule(),
            trackingModule(config.tracking, config.ws),
            tripModule(config.events),
            deviceControlModule(config.commands),
            liveModule(),
            routingModule(config.routing),
        )
    }
