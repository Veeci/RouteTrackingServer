package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.routing.application.api.PlannedRoute
import veeci.practicing.rts.routing.application.api.RouteMetric
import veeci.practicing.rts.routing.application.api.RoutePlanner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.PolylineCodec
import veeci.practicing.rts.trip.application.api.RouteSummary
import veeci.practicing.rts.trip.application.api.TripQuery
import veeci.practicing.rts.trip.application.api.TripSnapshot
import veeci.practicing.rts.trip.application.port.out.TripCursor
import veeci.practicing.rts.trip.application.port.out.TripEventOutbox
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripAction
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripRoute
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

data class RequestTrip(
    val pickup: GeoPoint,
    val destination: GeoPoint,
)

data class ActOnTrip(
    val tripId: TripId,
    val action: TripAction,
    /** The version the caller last saw (If-Match); null skips the check. */
    val expectedVersion: Long? = null,
)

data class TripPage(
    val items: List<TripSnapshot>,
    /** Where the next page starts; null on the last page. */
    val next: TripCursor?,
)

/**
 * The use cases of a trip. Each method is one transaction. Authorization happens here, with the [Actor]
 * the web layer passes in: the route only checks that the token is valid.
 */
class TripService(
    private val trips: TripRepository,
    private val outbox: TripEventOutbox,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val planner: RoutePlanner,
    private val routes: TripRouteRepository,
) : TripQuery {
    /**
     * Books a trip with its road route. The route is planned first, outside the transaction: planning is up to
     * 500 ms of CPU work, and a transaction open that long would hold one of the pool's few connections idle.
     * When no route exists the planner's DomainException (422) goes to the caller and no trip is stored.
     */
    suspend fun request(
        command: RequestTrip,
        actor: Actor,
    ): TripSnapshot {
        Trip.ensureMayRequest(actor) // before planning: a caller who may not book should cost no CPU
        val planned = planner.plan(command.pickup, command.destination, RouteMetric.DISTANCE)
        return transactions.inTransaction {
            val now = clock.now()
            val trip = Trip.request(actor, command.pickup, command.destination, newTripId(), now)
            trips.insert(trip)
            val route = planned.toTripRoute(trip.id, now)
            routes.insert(route)
            publish(trip)
            trip.snapshot(route = route)
        }
    }

    suspend fun get(
        id: TripId,
        actor: Actor,
    ): TripSnapshot =
        transactions.inTransaction {
            val trip = load(id, actor)
            trip.snapshot(route = routes.findLatest(trip.id, RouteKind.TRIP))
        }

    override suspend fun snapshotFor(
        tripId: UUID,
        actor: Actor,
    ): TripSnapshot = get(TripId(tripId), actor)

    /** The trips that wait for a driver, oldest first. Only drivers see this list. */
    suspend fun listOpen(
        actor: Actor,
        after: TripCursor?,
        limit: Int,
    ): TripPage {
        if (actor.role != Role.DRIVER) throw DomainException(TripError.TRIP_ACTION_FORBIDDEN, "Only drivers list open trips")
        return transactions.inTransaction {
            val found = trips.findByStatus(TripStatus.REQUESTED, after, limit + 1) // one more tells whether a next page exists
            val page = found.take(limit)
            TripPage(page.map { it.snapshot() }, page.lastOrNull()?.takeIf { found.size > limit }?.let { TripCursor(it.createdAt, it.id) })
        }
    }

    suspend fun act(
        command: ActOnTrip,
        actor: Actor,
    ): TripSnapshot =
        transactions.inTransaction {
            // A driver who lost the race for an open trip may no longer see it; they still get "already taken"
            // (ILLEGAL_TRANSITION from the aggregate) instead of "not found".
            val trip =
                if (command.action == TripAction.ACCEPT &&
                    actor.role == Role.DRIVER
                ) {
                    find(command.tripId)
                } else {
                    load(command.tripId, actor)
                }
            if (command.expectedVersion != null && command.expectedVersion != trip.version) {
                throw DomainException(TripError.VERSION_MISMATCH, "The trip has changed since version ${command.expectedVersion}")
            }
            trip.perform(command.action, actor, clock.now())
            if (command.action == TripAction.ACCEPT && trips.findActiveForDriver(actor.userId) != null) {
                throw DomainException(TripError.DRIVER_ALREADY_ON_TRIP, "Finish or cancel your current trip first")
            }
            if (!trips.update(trip)) {
                throw DomainException(TripError.CONCURRENT_MODIFICATION, "Someone changed this trip at the same time; read it again")
            }
            publish(trip)
            trip.snapshot(version = trip.version + 1)
        }

    private suspend fun load(
        id: TripId,
        actor: Actor,
    ): Trip = find(id).takeIf { it.isVisibleTo(actor) } ?: throw notFound(id)

    private suspend fun find(id: TripId): Trip = trips.find(id) ?: throw notFound(id)

    private fun notFound(id: TripId) = DomainException(TripError.TRIP_NOT_FOUND, "No trip ${id.value}")

    private suspend fun publish(trip: Trip) = trip.pullEvents().forEach { outbox.add(it) }
}

// Postgres keeps microseconds and rounds the rest. A Linux JVM clock has nanoseconds, so without the truncation a
// trip's times would read back different from what the API answered when it was created.
internal fun Clock.now(): Instant = instant().truncatedTo(ChronoUnit.MICROS).toKotlinInstant()

// Time-ordered ids (UUID version 7) keep new rows at the end of the primary key index.
@OptIn(ExperimentalUuidApi::class)
private fun newTripId() = TripId(Uuid.generateV7().toJavaUuid())

fun Trip.snapshot(
    version: Long = this.version,
    route: TripRoute? = null,
) = TripSnapshot(
    id.value,
    guestId.value,
    driverId?.value,
    pickup,
    destination,
    status,
    createdAt,
    updatedAt,
    version,
    route?.let { RouteSummary(it.polyline, it.distanceM, it.durationS) },
)

private fun PlannedRoute.toTripRoute(
    tripId: TripId,
    now: Instant,
) = TripRoute(tripId, RouteKind.TRIP, version = 1, PolylineCodec.encode(polyline), distanceM, durationS, now)
