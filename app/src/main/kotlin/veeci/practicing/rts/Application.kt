package veeci.practicing.rts

import com.sksamuel.hoplite.ConfigException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.plugin.KoinIsolated
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.config.AppConfig
import veeci.practicing.rts.platform.config.AppConfigLoader
import veeci.practicing.rts.platform.di.platformModule
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

    embeddedServer(Netty, port = config.http.port) { module(config) }.start(wait = true)
}

@Suppress("UnusedParameter")
fun Application.module(config: AppConfig) {
    install(KoinIsolated) {
        slf4jLogger()
        modules(appModule())
    }
    configureSerialization()
    configureStatusPages()
    configureWebsockets()
    configureRouting()
}

/** The whole object graph: the platform plus every bounded context. Production and the wiring test both use it. */
fun appModule(): Module =
    module {
        includes(platformModule())
    }
