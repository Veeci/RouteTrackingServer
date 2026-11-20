package veeci.practicing.rts.live.adapter.inbound.ws

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role

/** Mount inside `authenticate(AUTH_JWT)`: the upgrade request needs a valid token, and only a GUEST may stay. */
fun Route.guestSocket(socket: GuestSocket) {
    webSocket("/ws/v1/guest") { socket.serve(this, call.actor()) }
}

/**
 * The guest endpoint: a GUEST watches one of their own trips. Any other role gets close 4403. [TripWatcher] runs the
 * session, the same one staff get on `/ws/v1/admin/trip`.
 */
class GuestSocket(
    private val watcher: TripWatcher,
) {
    suspend fun serve(
        socket: DefaultWebSocketServerSession,
        actor: Actor,
    ) {
        if (actor.role != Role.GUEST) {
            socket.close(CloseReason(WsCloseCodes.FORBIDDEN_ROLE, "Only a GUEST may watch a trip"))
            return
        }
        watcher.serve(socket, actor, ENDPOINT)
    }

    private companion object {
        const val ENDPOINT = "guest"
    }
}
