package veeci.practicing.rts.live.adapter.inbound.ws

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.shared.Actor

/**
 * Mount inside `authenticate(AUTH_TICKET)`: the upgrade needs a ticket from `POST /api/v1/admin/ws-tickets`, as on the
 * fleet socket. A browser cannot set headers on a WebSocket, and a token in the URL would land in proxy logs.
 */
fun Route.adminTripSocket(socket: AdminTripSocket) {
    webSocket("/ws/v1/admin/trip") { socket.serve(this, call.actor()) }
}

/**
 * The staff trip endpoint (phase 12a, decision D8): an OPERATOR or ADMIN watches any trip live, with the guest's
 * protocol. Any other role gets close 4403. A separate endpoint keeps the guest socket for guests only, with the token
 * in the header, and keeps tickets off it.
 */
class AdminTripSocket(
    private val watcher: TripWatcher,
) {
    suspend fun serve(
        socket: DefaultWebSocketServerSession,
        actor: Actor,
    ) {
        if (!actor.role.isStaff) {
            socket.close(CloseReason(WsCloseCodes.FORBIDDEN_ROLE, "Only OPERATOR and ADMIN may watch a trip here"))
            return
        }
        watcher.serve(socket, actor, ENDPOINT)
    }

    private companion object {
        const val ENDPOINT = "admin_trip"
    }
}
