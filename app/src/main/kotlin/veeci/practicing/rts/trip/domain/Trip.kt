package veeci.practicing.rts.trip.domain

import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.event.TripStatus.ACCEPTED
import veeci.practicing.rts.trip.domain.event.TripStatus.ARRIVED
import veeci.practicing.rts.trip.domain.event.TripStatus.CANCELLED
import veeci.practicing.rts.trip.domain.event.TripStatus.COMPLETED
import veeci.practicing.rts.trip.domain.event.TripStatus.IN_PROGRESS
import veeci.practicing.rts.trip.domain.event.TripStatus.REQUESTED
import veeci.practicing.rts.trip.domain.event.TripStatusChanged
import java.util.UUID
import kotlin.time.Instant

@JvmInline
value class TripId(
    val value: UUID,
)

/** What a user can do to a trip. Each action moves the trip to one next stage (see [Trip]). */
enum class TripAction { ACCEPT, ARRIVE, START, COMPLETE, CANCEL }

/**
 * A ride from [pickup] to [destination] for one guest. It moves through its stages only by the actions below,
 * and it checks two things for every action, in this order:
 * 1. Is the action possible in the current stage? If not: ILLEGAL_TRANSITION.
 * 2. May this actor do it? If not: TRIP_ACTION_FORBIDDEN.
 *
 * | From                         | Action   | Who                      | To          |
 * |------------------------------|----------|--------------------------|-------------|
 * | REQUESTED                    | ACCEPT   | any driver (assigned)    | ACCEPTED    |
 * | ACCEPTED                     | ARRIVE   | the assigned driver      | ARRIVED     |
 * | ARRIVED                      | START    | the assigned driver      | IN_PROGRESS |
 * | IN_PROGRESS                  | COMPLETE | the assigned driver      | COMPLETED   |
 * | REQUESTED, ACCEPTED, ARRIVED | CANCEL   | the guest or the driver  | CANCELLED   |
 *
 * Every change records a [TripStatusChanged]; the service writes it to the outbox in the same transaction.
 */
class Trip private constructor(
    val id: TripId,
    val guestId: UserId,
    driverId: UserId?,
    val pickup: GeoPoint,
    val destination: GeoPoint,
    status: TripStatus,
    val createdAt: Instant,
    updatedAt: Instant,
    /** The version this copy was read with. Saving checks that the stored trip still has it. */
    val version: Long,
) {
    var driverId: UserId? = driverId
        private set

    var status: TripStatus = status
        private set

    var updatedAt: Instant = updatedAt
        private set

    private val events = mutableListOf<TripStatusChanged>()

    fun perform(
        action: TripAction,
        actor: Actor,
        now: Instant,
    ) {
        val target =
            TRANSITIONS[status to action]
                ?: throw DomainException(TripError.ILLEGAL_TRANSITION, "A trip that is $status cannot be ${action.done()}")
        if (!mayPerform(action, actor)) {
            throw DomainException(TripError.TRIP_ACTION_FORBIDDEN, "You may not ${action.name.lowercase()} this trip")
        }
        if (action == TripAction.ACCEPT) driverId = actor.userId
        record(from = status, to = target, now)
        status = target
        updatedAt = now
    }

    /**
     * Whether [actor] may know that this trip exists. A guest sees their own trips, a driver sees open
     * (REQUESTED) trips and the trips assigned to them, an admin sees every trip.
     */
    fun isVisibleTo(actor: Actor): Boolean =
        when (actor.role) {
            Role.ADMIN -> true
            Role.GUEST -> actor.userId == guestId
            Role.DRIVER -> status == REQUESTED || actor.userId == driverId
        }

    /** The changes since the trip was created or loaded, each returned once. */
    fun pullEvents(): List<TripStatusChanged> = events.toList().also { events.clear() }

    private fun mayPerform(
        action: TripAction,
        actor: Actor,
    ): Boolean {
        val isAssignedDriver = actor.role == Role.DRIVER && actor.userId == driverId
        return when (action) {
            TripAction.ACCEPT -> actor.role == Role.DRIVER
            TripAction.ARRIVE, TripAction.START, TripAction.COMPLETE -> isAssignedDriver
            TripAction.CANCEL -> isAssignedDriver || (actor.role == Role.GUEST && actor.userId == guestId)
        }
    }

    private fun record(
        from: TripStatus?,
        to: TripStatus,
        at: Instant,
    ) {
        events += TripStatusChanged(id.value, guestId.value, driverId?.value, from, to, at)
    }

    private fun TripAction.done(): String =
        when (this) {
            TripAction.ACCEPT -> "accepted"
            TripAction.ARRIVE -> "marked as arrived"
            TripAction.START -> "started"
            TripAction.COMPLETE -> "completed"
            TripAction.CANCEL -> "cancelled"
        }

    companion object {
        /** The legal moves: (current stage, action) → next stage. Everything else is ILLEGAL_TRANSITION. */
        val TRANSITIONS: Map<Pair<TripStatus, TripAction>, TripStatus> =
            mapOf(
                (REQUESTED to TripAction.ACCEPT) to ACCEPTED,
                (ACCEPTED to TripAction.ARRIVE) to ARRIVED,
                (ARRIVED to TripAction.START) to IN_PROGRESS,
                (IN_PROGRESS to TripAction.COMPLETE) to COMPLETED,
                (REQUESTED to TripAction.CANCEL) to CANCELLED,
                (ACCEPTED to TripAction.CANCEL) to CANCELLED,
                (ARRIVED to TripAction.CANCEL) to CANCELLED,
            )

        /** A guest asks for a ride. Only a guest may. */
        fun request(
            guest: Actor,
            pickup: GeoPoint,
            destination: GeoPoint,
            id: TripId,
            now: Instant,
        ): Trip {
            if (guest.role != Role.GUEST) throw DomainException(TripError.TRIP_ACTION_FORBIDDEN, "Only a guest may request a trip")
            return Trip(id, guest.userId, null, pickup, destination, REQUESTED, now, now, version = 1)
                .also { it.record(from = null, to = REQUESTED, now) }
        }

        /** Rebuilds a stored trip exactly as it was saved. No rule runs and no event is recorded. */
        @Suppress("LongParameterList") // one parameter per stored column
        fun restore(
            id: TripId,
            guestId: UserId,
            driverId: UserId?,
            pickup: GeoPoint,
            destination: GeoPoint,
            status: TripStatus,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): Trip = Trip(id, guestId, driverId, pickup, destination, status, createdAt, updatedAt, version)
    }
}
