package veeci.practicing.rts.testing.trip

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.application.port.out.RuleStateStore
import veeci.practicing.rts.trip.application.port.out.TripCursor
import veeci.practicing.rts.trip.application.port.out.TripEventOutbox
import veeci.practicing.rts.trip.application.port.out.TripMomentRepository
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.application.port.out.TripRouteRepository
import veeci.practicing.rts.trip.domain.ArrivalState
import veeci.practicing.rts.trip.domain.BatteryState
import veeci.practicing.rts.trip.domain.PresenceState
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripRoute
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.event.TripStatusChanged
import kotlin.time.Instant

/**
 * The trip repository in memory. It keeps the rules the database enforces (version check, one active trip
 * per driver); TripRepositoryContract runs the same tests against this class and the Postgres one.
 */
class InMemoryTripRepository : TripRepository {
    /** A stored copy, like a table row: changes to a loaded trip are lost unless it is saved again. */
    private data class Row(
        val id: TripId,
        val guestId: UserId,
        val driverId: UserId?,
        val pickup: GeoPoint,
        val destination: GeoPoint,
        val status: TripStatus,
        val createdAt: Instant,
        val updatedAt: Instant,
        val version: Long,
    ) {
        fun toTrip() = Trip.restore(id, guestId, driverId, pickup, destination, status, createdAt, updatedAt, version)
    }

    private val mutex = Mutex()
    private val rows = mutableMapOf<TripId, Row>()

    override suspend fun insert(trip: Trip) =
        mutex.withLock {
            check(trip.id !in rows) { "Trip ${trip.id} exists already" }
            rows[trip.id] = trip.toRow(trip.version)
        }

    override suspend fun find(id: TripId): Trip? = mutex.withLock { rows[id]?.toTrip() }

    override suspend fun findByStatus(
        status: TripStatus,
        after: TripCursor?,
        limit: Int,
    ): List<Trip> =
        mutex.withLock {
            rows.values
                .filter { it.status == status }
                .sortedWith(compareBy({ it.createdAt }, { it.id.sortKey() }))
                .filter {
                    after == null || it.createdAt > after.createdAt ||
                        (it.createdAt == after.createdAt && it.id.sortKey() > after.id.sortKey())
                }.take(limit)
                .map { it.toTrip() }
        }

    override suspend fun findActive(): List<Trip> = mutex.withLock { rows.values.filter { it.status.isActive }.map { it.toTrip() } }

    override suspend fun findActiveForDriver(driver: UserId): Trip? =
        mutex.withLock { rows.values.firstOrNull { it.driverId == driver && it.status.isActive }?.toTrip() }

    override suspend fun update(trip: Trip): Boolean =
        mutex.withLock {
            val stored = rows[trip.id]
            if (stored == null || stored.version != trip.version) return@withLock false
            val driver = trip.driverId
            if (driver != null && trip.status.isActive &&
                rows.values.any { it.id != trip.id && it.driverId == driver && it.status.isActive }
            ) {
                throw DomainException(TripError.DRIVER_ALREADY_ON_TRIP, "The driver already has an active trip")
            }
            rows[trip.id] = trip.toRow(trip.version + 1)
            true
        }

    // Postgres orders uuids byte by byte, which is the order of their text form. java.util.UUID.compareTo
    // compares two signed longs instead, so "8b57..." would come before "1d74...".
    private fun TripId.sortKey(): String = value.toString()

    private fun Trip.toRow(version: Long) = Row(id, guestId, driverId, pickup, destination, status, createdAt, updatedAt, version)
}

/** Keeps the events and moments, in order, for assertions. */
class InMemoryTripEventOutbox : TripEventOutbox {
    val events = mutableListOf<TripStatusChanged>()
    val moments = mutableListOf<TripMoment>()

    override suspend fun add(event: TripStatusChanged) {
        events += event
    }

    override suspend fun add(moment: TripMoment) {
        moments += moment
    }
}

/** The stored moments, in order. */
class InMemoryTripMomentRepository : TripMomentRepository {
    val moments = mutableListOf<TripMoment>()

    override suspend fun append(moment: TripMoment) {
        moments += moment
    }
}

/** Rule state per trip. No locking: the unit tests run one batch at a time. */
class InMemoryRuleStateStore : RuleStateStore {
    val arrival = mutableMapOf<TripId, ArrivalState>()
    val battery = mutableMapOf<TripId, BatteryState>()
    val presence = mutableMapOf<TripId, PresenceState>()

    override suspend fun lockPresence(trip: TripId): PresenceState = presence[trip] ?: PresenceState()

    override suspend fun savePresence(
        trip: TripId,
        state: PresenceState,
    ) {
        presence[trip] = state
    }

    override suspend fun lockArrival(trip: TripId): ArrivalState = arrival[trip] ?: ArrivalState()

    override suspend fun saveArrival(
        trip: TripId,
        state: ArrivalState,
    ) {
        arrival[trip] = state
    }

    override suspend fun lockBattery(trip: TripId): BatteryState = battery[trip] ?: BatteryState()

    override suspend fun saveBattery(
        trip: TripId,
        state: BatteryState,
    ) {
        battery[trip] = state
    }
}

/** Routes by trip, kind and version, like the `trip_routes` primary key. */
class InMemoryTripRouteRepository : TripRouteRepository {
    val routes = mutableListOf<TripRoute>()

    override suspend fun insert(route: TripRoute) {
        check(routes.none { it.tripId == route.tripId && it.kind == route.kind && it.version == route.version }) {
            "Route ${route.kind} v${route.version} of ${route.tripId} exists already"
        }
        routes += route
    }

    override suspend fun findLatest(
        tripId: TripId,
        kind: RouteKind,
    ): TripRoute? = routes.filter { it.tripId == tripId && it.kind == kind }.maxByOrNull { it.version }
}
