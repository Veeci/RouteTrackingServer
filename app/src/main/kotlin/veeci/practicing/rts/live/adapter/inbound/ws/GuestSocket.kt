package veeci.practicing.rts.live.adapter.inbound.ws

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import veeci.practicing.rts.devicecontrol.application.api.GuestWatching
import veeci.practicing.rts.live.application.api.DriverPositionUpdate
import veeci.practicing.rts.live.application.api.LiveUpdate
import veeci.practicing.rts.live.application.api.TripMomentUpdate
import veeci.practicing.rts.live.application.api.TripProgressUpdate
import veeci.practicing.rts.live.application.api.TripStatusUpdate
import veeci.practicing.rts.live.application.port.out.LiveUpdateBus
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.platform.ws.WsSession
import veeci.practicing.rts.platform.ws.WsSessionRunner
import veeci.practicing.rts.protocol.DriverPosition
import veeci.practicing.rts.protocol.GuestClientMessage
import veeci.practicing.rts.protocol.GuestServerMessage
import veeci.practicing.rts.protocol.GuestTrip
import veeci.practicing.rts.protocol.Point
import veeci.practicing.rts.protocol.Subscribe
import veeci.practicing.rts.protocol.Subscribed
import veeci.practicing.rts.protocol.TripEvent
import veeci.practicing.rts.protocol.TripProgress
import veeci.practicing.rts.protocol.WsCloseCodes
import veeci.practicing.rts.protocol.WsErrorCodes
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.application.api.TripQuery
import veeci.practicing.rts.trip.application.api.TripSnapshot
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.util.UUID

/** Mount inside `authenticate(AUTH_JWT)`: the upgrade request needs a valid token, and only a GUEST may stay. */
fun Route.guestSocket(socket: GuestSocket) {
    webSocket("/ws/v1/guest") { socket.serve(this, call.actor()) }
}

/**
 * The guest endpoint: `subscribe` to one of the guest's own trips, get `subscribed` with the trip as it is, then
 * live `driver_position`, `trip_progress` and `trip_event` messages until the trip ends; then the server closes the
 * socket with 1000. Whether the guest may watch the trip is the trip context's decision (TripQuery), not this
 * socket's.
 */
class GuestSocket(
    private val runner: WsSessionRunner,
    private val trips: TripQuery,
    private val bus: LiveUpdateBus,
    private val watching: GuestWatching,
) {
    private val log = LoggerFactory.getLogger(GuestSocket::class.java)

    suspend fun serve(
        socket: DefaultWebSocketServerSession,
        actor: Actor,
    ) {
        if (actor.role != Role.GUEST) {
            socket.close(CloseReason(WsCloseCodes.FORBIDDEN_ROLE, "Only a GUEST may watch a trip"))
            return
        }
        var watching: Job? = null
        runner.run(socket, ENDPOINT, GuestClientMessage.serializer()) { message ->
            when (message) {
                is Subscribe -> {
                    if (watching == null) {
                        watching = subscribe(socket, message, actor)
                    } else {
                        sendError(WsErrorCodes.INVALID_MESSAGE, "This socket already watches a trip; open another socket for another trip.")
                    }
                }
            }
        }
        // The watcher runs in the socket's scope, so it stops when the socket closes.
    }

    /** Sends `subscribed` and starts forwarding live updates. Null if the guest may not watch this trip. */
    private suspend fun WsSession.subscribe(
        socket: DefaultWebSocketServerSession,
        message: Subscribe,
        actor: Actor,
    ): Job? {
        val tripId = runCatching { UUID.fromString(message.tripId) }.getOrNull()
        val trip =
            if (tripId == null) {
                sendError(WsErrorCodes.INVALID_MESSAGE, "tripId must be a UUID.")
                null
            } else {
                snapshot(tripId, actor) // null after an error frame: not found, or not this guest's trip
            }
        if (tripId == null || trip == null) return null
        send(Subscribed(trip.toGuestTrip()))
        return if (trip.status.isTerminal) {
            finish(trip)
            null
        } else {
            log.info("Guest {} watches trip {}", actor.userId.value, tripId)
            socket.launch { watch(tripId, trip, actor) }
        }
    }

    /**
     * Forwards the trip's updates until it ends or the socket closes. The first guest watching a trip on this
     * instance, and the last one leaving it, tell device control, which changes the driver's tracking precision.
     */
    private suspend fun WsSession.watch(
        tripId: UUID,
        trip: TripSnapshot,
        actor: Actor,
    ) {
        val driver = trip.driverId?.let(::UserId)
        var status = trip.status
        if (driver != null && bus.subscriberCount(tripId) == 0) watching.firstGuestWatching(driver)
        try {
            // takeWhile stops collecting (and unsubscribes) after the update that ended the trip.
            bus
                .subscribe(tripId)
                .takeWhile { update ->
                    if (update is TripStatusUpdate) status = update.status
                    forward(update, actor)
                }.collect {}
        } finally {
            // Runs also when the socket was cancelled; the call is quick and never throws.
            if (driver != null && bus.subscriberCount(tripId) == 0) {
                withContext(NonCancellable) { watching.lastGuestLeft(driver, rideStarted = status == TripStatus.IN_PROGRESS) }
            }
        }
    }

    /** Sends one update; false once the trip has ended (the socket is closed then). */
    private suspend fun WsSession.forward(
        update: LiveUpdate,
        actor: Actor,
    ): Boolean =
        when (update) {
            is DriverPositionUpdate -> {
                send(
                    DriverPosition(
                        update.tripId.toString(),
                        update.point.lat,
                        update.point.lng,
                        update.bearingDeg,
                        update.speedMps,
                        update.recordedAt,
                    ),
                )
                true
            }

            is TripProgressUpdate -> {
                send(TripProgress(update.tripId.toString(), update.target, update.remainingM, update.etaS, update.basis))
                true
            }

            is TripMomentUpdate -> {
                // The protocol names events in lower case: DRIVER_ARRIVING travels as driver_arriving.
                send(
                    TripEvent(
                        update.tripId.toString(),
                        update.kind.lowercase(),
                        update.status.name,
                        update.at,
                        update.remainingM,
                        update.levelPercent,
                        update.lastSeenAt,
                    ),
                )
                true
            }

            is TripStatusUpdate -> {
                // Read the trip again: the update may be an old one (replayed or repeated), the trip is the truth.
                val trip = snapshot(update.tripId, actor)
                if (trip == null) {
                    close(CloseReason(CloseReason.Codes.NORMAL, "Trip no longer visible"))
                    false
                } else {
                    send(TripEvent(trip.id.toString(), TripEvent.STATUS_CHANGED, trip.status.name, trip.updatedAt))
                    if (trip.status.isTerminal) finish(trip)
                    !trip.status.isTerminal
                }
            }
        }

    /** The trip as this guest may see it, or null after telling the guest why not. */
    private suspend fun WsSession.snapshot(
        tripId: UUID,
        actor: Actor,
    ): TripSnapshot? =
        try {
            trips.snapshotFor(tripId, actor)
        } catch (e: DomainException) {
            sendError(e.code.name, e.message ?: e.code.name)
            null
        }

    private suspend fun WsSession.finish(trip: TripSnapshot) = close(CloseReason(CloseReason.Codes.NORMAL, "Trip ${trip.status}"))

    private suspend fun WsSession.send(message: GuestServerMessage) = send(GuestServerMessage.serializer(), message)

    private fun TripSnapshot.toGuestTrip() =
        GuestTrip(id.toString(), status.name, pickup.toPoint(), destination.toPoint(), driverId?.toString(), updatedAt)

    private fun GeoPoint.toPoint() = Point(lat, lng)

    private companion object {
        const val ENDPOINT = "guest"
    }
}
