package veeci.practicing.rts.fleet.adapter.inbound.ws

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import veeci.practicing.rts.fleet.application.FleetLive
import veeci.practicing.rts.fleet.application.FleetSink
import veeci.practicing.rts.fleet.application.FleetUpdate
import veeci.practicing.rts.fleet.domain.Bbox
import veeci.practicing.rts.fleet.domain.FleetMarker
import veeci.practicing.rts.fleet.domain.MapView
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.platform.ws.WsSession
import veeci.practicing.rts.platform.ws.WsSessionRunner
import veeci.practicing.rts.protocol.DriverMarker
import veeci.practicing.rts.protocol.FleetCell
import veeci.practicing.rts.protocol.FleetClientMessage
import veeci.practicing.rts.protocol.FleetCluster
import veeci.practicing.rts.protocol.FleetDelta
import veeci.practicing.rts.protocol.FleetServerMessage
import veeci.practicing.rts.protocol.FleetSnapshot
import veeci.practicing.rts.protocol.FleetSubscribe
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.shared.Actor

/**
 * Mount inside `authenticate(AUTH_TICKET)`: the upgrade needs a ticket from `POST /api/v1/admin/ws-tickets`, and only
 * staff may stay. A browser cannot set headers on a WebSocket, and a token in the URL would land in proxy logs.
 */
fun Route.fleetSocket(socket: FleetSocket) {
    webSocket("/ws/v1/admin/fleet") { socket.serve(this, call.actor()) }
}

/**
 * The admin fleet endpoint (phase 11c): `fleet_subscribe` with a map area, then a `fleet_snapshot` and a `fleet_delta`
 * every tick, or `fleet_cluster` for a crowded area. What to send is [FleetLive]'s decision; this class only speaks the
 * protocol.
 */
class FleetSocket(
    private val runner: WsSessionRunner,
    private val live: FleetLive,
) {
    suspend fun serve(
        socket: DefaultWebSocketServerSession,
        actor: Actor,
    ) {
        if (!actor.role.isStaff) {
            socket.close(CloseReason(WsCloseCodes.FORBIDDEN_ROLE, "Only OPERATOR and ADMIN may watch the fleet"))
            return
        }
        val subscriber = Subscriber(live)
        try {
            runner.run(socket, ENDPOINT, FleetClientMessage.serializer()) { message ->
                when (message) {
                    is FleetSubscribe -> subscriber.subscribe(this, message)
                }
            }
        } finally {
            subscriber.close()
        }
    }

    private companion object {
        const val ENDPOINT = "fleet"
    }
}

/** One socket's subscription; it registers with [FleetLive] on the first `fleet_subscribe`. */
private class Subscriber(
    private val live: FleetLive,
) {
    private var watch: FleetLive.Watch? = null

    suspend fun subscribe(
        session: WsSession,
        message: FleetSubscribe,
    ) {
        val view = message.toView()
        if (view == null) {
            session.sendError(
                WsErrorCodes.INVALID_MESSAGE,
                "bbox must be [minLng, minLat, maxLng, maxLat] within the world, minimum first.",
            )
        } else {
            val current = watch ?: live.open(SocketSink(session)).also { watch = it }
            current.subscribe(view)
        }
    }

    fun close() {
        watch?.close()
    }

    private fun FleetSubscribe.toView(): MapView? {
        val area =
            bbox.takeIf { it.size == BBOX_PARTS }?.let { parts ->
                val (minLng, minLat) = parts.take(2)
                val (maxLng, maxLat) = parts.drop(2)
                runCatching { Bbox(minLng, minLat, maxLng, maxLat) }.getOrNull()
            }
        return area?.let { MapView(it, filters.online, filters.onTrip) }
    }

    private companion object {
        const val BBOX_PARTS = 4
    }
}

/** Sends [FleetUpdate]s as protocol messages on one socket. */
private class SocketSink(
    private val session: WsSession,
) : FleetSink {
    override suspend fun send(update: FleetUpdate) = session.send(FleetServerMessage.serializer(), update.toMessage())

    /**
     * Queues a close frame without waiting. When even that does not fit (the client reads nothing at all), the
     * session is cancelled and the connection dropped.
     */
    override fun tooSlow() {
        if (!session.goAway(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too slow to read the fleet updates"))) session.cancel()
    }

    private fun FleetUpdate.toMessage(): FleetServerMessage =
        when (this) {
            is FleetUpdate.Snapshot -> FleetSnapshot(markers.map { it.toMessage() }, at)
            is FleetUpdate.Delta -> FleetDelta(upserts.map { it.toMessage() }, removes.map { it.toString() }, at)
            is FleetUpdate.Clusters -> FleetCluster(cells.map { FleetCell(it.lat, it.lng, it.count) }, cellDeg, total, at)
        }

    private fun FleetMarker.toMessage() =
        DriverMarker(driverId.toString(), lat, lng, bearingDeg, online, tripStatus, batteryPct, lastSeenAt, heardAt)
}
