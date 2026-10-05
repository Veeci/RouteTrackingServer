package veeci.practicing.rts.platform.lifecycle

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.websocket.CloseReason
import org.koin.ktor.ext.get
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.observability.HealthRegistry
import veeci.practicing.rts.platform.ws.WsSessionRegistry
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger("veeci.practicing.rts.platform.lifecycle.GracefulShutdown")

/**
 * What happens when the platform stops this instance (SIGTERM on a deploy or scale-down). Ktor raises
 * ApplicationStopPreparing first, then stops accepting connections and gives in-flight requests up to
 * http.shutdownTimeout to finish; only after that does Koin close the connection pool. At that first
 * moment, while connections still work:
 * 1. readiness turns DOWN, so load balancers stop routing new traffic here;
 * 2. every WebSocket gets close 1001 (going away), so clients reconnect with backoff, to another
 *    instance, instead of seeing a dropped connection.
 */
fun Application.configureGracefulShutdown() {
    val health = get<HealthRegistry>()
    val sockets = get<WsSessionRegistry>()
    val started = AtomicBoolean(false)
    // Ktor raises this event from more than one layer of its stop sequence; act on the first only.
    monitor.subscribe(ApplicationStopPreparing) {
        if (!started.compareAndSet(false, true)) return@subscribe
        health.markStopping()
        val asked = sockets.closeAll(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
        log.info("Shutting down: readiness is DOWN, {} WebSocket session(s) asked to reconnect", asked)
    }
}
