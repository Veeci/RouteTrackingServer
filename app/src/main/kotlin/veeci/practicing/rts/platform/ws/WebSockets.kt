package veeci.practicing.rts.platform.ws

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.json.JsonObject
import veeci.practicing.rts.platform.config.WsConfig

fun Application.configureWebSockets(config: WsConfig) {
    install(WebSockets) {
        pingPeriod = config.pingPeriod
        timeout = config.timeout
        maxFrameSize = config.maxFrameBytes // bigger frames close the session with 1009
        masking = false // only clients mask frames
    }
}

/**
 * Diagnostic endpoint: sends every JSON object straight back. Lets SDK developers check connectivity,
 * keepalive and limits before real endpoints exist. Never mounted in prod.
 */
fun Route.echoEndpoint(runner: WsSessionRunner) {
    webSocket("/ws/v1/echo") {
        runner.run(this, endpoint = "echo", inbound = JsonObject.serializer()) { message ->
            send(JsonObject.serializer(), message)
        }
    }
}
