package veeci.practicing.rts.fleet.application

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import veeci.practicing.rts.fleet.application.port.out.FleetRepository
import veeci.practicing.rts.fleet.domain.FleetCursor
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.fleet.domain.FleetError
import veeci.practicing.rts.fleet.domain.FleetFilter
import veeci.practicing.rts.identity.application.api.DriverAccount
import veeci.practicing.rts.identity.application.api.DriverAccounts
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.telemetry.application.api.DriverHealth
import veeci.practicing.rts.telemetry.application.api.HealthOverview
import veeci.practicing.rts.tracking.application.api.DriverActivity
import veeci.practicing.rts.tracking.application.api.TelemetrySession
import veeci.practicing.rts.trip.application.api.DriverTrips
import veeci.practicing.rts.trip.application.api.TripSnapshot
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/** A driver of the read model and whether they count as online at the time of the read. */
data class FleetRow(
    val driver: FleetDriver,
    val online: Boolean,
)

/** One page of the driver list. */
data class FleetPage(
    val items: List<FleetRow>,
    /** Where the next page starts; null on the last page. */
    val next: FleetCursor?,
    /** The time "online" was judged against. */
    val at: Instant,
)

/** One driver with what other contexts know about them, each read through that context's public API. */
data class DriverDetail(
    val driver: FleetRow,
    val account: DriverAccount?,
    val sessions: List<TelemetrySession>,
    val trips: List<TripSnapshot>,
    val health: HealthOverview?,
    val at: Instant,
)

data class FleetOverview(
    val onlineDrivers: Int,
    val driversOnTrip: Int,
    val activeTrips: Int,
    val disabledDrivers: Int,
    /** When the numbers were counted; up to [FleetService.OVERVIEW_TTL] old. */
    val computedAt: Instant,
)

/**
 * Reads the fleet view for staff (phase 11): the driver list, one driver's detail, the overview and the CSV export.
 * The overview is cached for [OVERVIEW_TTL] on each instance, so a wall of dashboards costs one count per instance
 * every 10 s.
 */
class FleetService(
    private val fleet: FleetRepository,
    private val accounts: DriverAccounts,
    private val activity: DriverActivity,
    private val trips: DriverTrips,
    private val health: DriverHealth,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val staleAfter: Duration,
) {
    private val overviewLock = Mutex()

    @Volatile
    private var overview: FleetOverview? = null

    suspend fun list(
        actor: Actor,
        filter: FleetFilter,
        after: FleetCursor?,
        limit: Int,
    ): FleetPage {
        requireStaff(actor)
        val now = now()
        val found = transactions.inTransaction { fleet.page(filter, now - staleAfter, after, limit + 1) }
        val page = found.take(limit)
        val next = page.lastOrNull()?.takeIf { found.size > limit }?.let { FleetCursor(it.lastSeenAt, it.driverId) }
        return FleetPage(page.map { FleetRow(it, it.isOnline(now, staleAfter)) }, next, now)
    }

    suspend fun detail(
        actor: Actor,
        driverId: UUID,
    ): DriverDetail {
        requireStaff(actor)
        val driver = transactions.inTransaction { fleet.find(driverId) }
        val account = accounts.driver(UserId(driverId))
        if (driver == null && account == null) throw DomainException(FleetError.DRIVER_NOT_FOUND, "No driver $driverId")
        // An account whose events have not reached the read model yet still has a detail page.
        val stored = driver ?: FleetDriver(driverId, account?.displayName)
        // The row learns of a disable one outbox event later. The account read above is current, so its flag wins and
        // agrees with the account's disabledAt in the same answer. The driver list keeps the row's value.
        val row = account?.let { stored.copy(enabled = it.enabled) } ?: stored
        val now = now()
        return DriverDetail(
            driver = FleetRow(row, row.isOnline(now, staleAfter)),
            account = account,
            sessions = activity.recentSessions(driverId, RECENT),
            trips = trips.recentForDriver(driverId, RECENT),
            health = health.latestFor(driverId),
            at = now,
        )
    }

    suspend fun overview(actor: Actor): FleetOverview {
        requireStaff(actor)
        fresh()?.let { return it }
        // One caller counts; the others wait and take its result.
        return overviewLock.withLock {
            fresh() ?: count().also { overview = it }
        }
    }

    /** Checks that [actor] may export now, so a refusal is a 403 before the CSV starts; the export reads page by page. */
    fun export(actor: Actor): FleetExport {
        requireAdmin(actor)
        return FleetExport { after ->
            val now = now()
            transactions
                .inTransaction { fleet.page(FleetFilter(), now - staleAfter, after, EXPORT_PAGE) }
                .map { FleetRow(it, it.isOnline(now, staleAfter)) }
        }
    }

    private fun fresh(): FleetOverview? = overview?.takeIf { now() - it.computedAt < OVERVIEW_TTL }

    private suspend fun count(): FleetOverview {
        val now = now()
        val counts = transactions.inTransaction { fleet.counts(now - staleAfter) }
        return FleetOverview(counts.online, counts.onTrip, trips.activeCount(), counts.disabled, now)
    }

    private fun now(): Instant = clock.instant().toKotlinInstant()

    companion object {
        val OVERVIEW_TTL = 10.seconds
        private const val RECENT = 10
        private const val EXPORT_PAGE = 500

        internal fun requireStaff(actor: Actor) {
            if (!actor.role.isStaff) throw DomainException(FleetError.FLEET_FORBIDDEN, "The fleet view is for operators and admins")
        }

        internal fun requireAdmin(actor: Actor) {
            if (actor.role != Role.ADMIN) throw DomainException(FleetError.FLEET_FORBIDDEN, "Only an admin may do this")
        }
    }
}

/** Every driver in list order, one page per transaction, so a long download never holds one transaction open. */
class FleetExport(
    private val page: suspend (after: FleetCursor?) -> List<FleetRow>,
) {
    suspend fun forEachPage(block: suspend (List<FleetRow>) -> Unit) {
        var after: FleetCursor? = null
        while (true) {
            val rows = page(after)
            if (rows.isEmpty()) return
            block(rows)
            after = rows.last().driver.let { FleetCursor(it.lastSeenAt, it.driverId) }
        }
    }
}
