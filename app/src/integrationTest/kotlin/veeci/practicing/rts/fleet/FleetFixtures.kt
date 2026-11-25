package veeci.practicing.rts.fleet

import io.ktor.server.application.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.ktor.ext.get
import veeci.practicing.rts.fleet.application.FleetPresence
import veeci.practicing.rts.fleet.application.port.out.FleetPresenceRepository
import veeci.practicing.rts.fleet.application.port.out.FleetRepository
import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.fleet.domain.TripState
import veeci.practicing.rts.platform.db.TransactionRunner
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Builds and reads fleet rows for the fleet's integration tests. */
object FleetFixtures {
    /**
     * The current time in whole milliseconds. Postgres keeps microseconds, and the clock on Linux (CI) has nanoseconds,
     * so a time read back from a row would differ from an untruncated one. The clock on macOS has only microseconds,
     * which hid this locally.
     */
    fun now(): Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /**
     * A driver whose position and status were measured [ago] (null: never), at [lat], [lng], optionally on a trip. The
     * server heard from them [heard] ago (null: never), by default when the data was measured.
     */
    fun driver(
        name: String? = null,
        ago: Duration? = 5.seconds,
        heard: Duration? = ago,
        lat: Double = 21.03,
        lng: Double = 105.85,
        onTrip: Boolean = false,
        enabled: Boolean = true,
        id: UUID = UUID.randomUUID(),
    ): FleetDriver {
        val seen = ago?.let { now() - it }
        return FleetDriver(
            driverId = id,
            displayName = name,
            enabled = enabled,
            position = seen?.let { DriverPosition(lat, lng, 8.5, 90.0, it) },
            device = seen?.let { DeviceState(80, false, "IN_VEHICLE", it) },
            trip = if (onTrip) TripState(UUID.randomUUID(), "ACCEPTED", now() - 1.minutes) else null,
            heardAt = heard?.let { now() - it },
        )
    }

    suspend fun Application.seed(vararg drivers: FleetDriver) = seed(drivers.toList())

    /** Writes the rows, then their `heard_at` the way the presence flush does. */
    suspend fun Application.seed(drivers: List<FleetDriver>) {
        val now = Clock.System.now()
        get<TransactionRunner>().inTransaction {
            get<FleetRepository>().insertAll(drivers, now)
            get<FleetPresenceRepository>().saveHeard(drivers.mapNotNull { d -> d.heardAt?.let { d.driverId to it } }.toMap(), now)
        }
    }

    /** Writes what ingest has told the fleet's presence so far, without waiting for its next flush. */
    suspend fun Application.flushPresence() = get<FleetPresence>().flush()

    suspend fun Application.fleetRow(id: UUID): FleetDriver? = get<TransactionRunner>().inTransaction { get<FleetRepository>().find(id) }

    /** The row's server write time, which the domain model does not carry. */
    suspend fun Application.updatedAt(id: UUID): Instant? =
        sql("SELECT updated_at FROM fleet_drivers WHERE driver_id = '$id'") { it.getTimestamp(1).toInstant().toString() }
            .singleOrNull()
            ?.let(Instant::parse)

    /** Every row of the read model with every column but `updated_at`, in driver order, as text. */
    suspend fun Application.fleetTable(): List<String> =
        sql(
            """
            SELECT driver_id, display_name, enabled, last_seen_at, lat, lng, speed_mps, bearing_deg, battery_pct, is_charging,
                   motion, position_at, status_at, current_trip_id, trip_status, trip_status_at, heard_at
            FROM fleet_drivers ORDER BY driver_id
            """.trimIndent(),
        ) { rows -> (1..COLUMNS).joinToString("|") { rows.getString(it).orEmpty() } }

    /** Waits until the outbox relay has delivered every stored event. */
    suspend fun Application.awaitOutboxDrained() {
        withTimeout(DRAIN_TIMEOUT) {
            while (sql("SELECT count(*) FROM outbox WHERE published_at IS NULL") { it.getLong(1) }.single() > 0) delay(POLL)
        }
    }

    private suspend fun <T> Application.sql(
        query: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        get<TransactionRunner>().inTransaction {
            TransactionManager.current().exec(query) { rows -> buildList { while (rows.next()) add(read(rows)) } }.orEmpty()
        }

    private const val COLUMNS = 17
    private val DRAIN_TIMEOUT = 15.seconds
    private val POLL = 50.milliseconds
}
