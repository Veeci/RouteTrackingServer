package veeci.practicing.rts.fleet.application

import veeci.practicing.rts.fleet.application.port.out.FleetRepository
import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.identity.application.api.DriverAccounts
import veeci.practicing.rts.platform.audit.AuditEntry
import veeci.practicing.rts.platform.audit.AuditLog
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.tracking.application.api.DriverActivity
import veeci.practicing.rts.trip.application.api.DriverTrips
import java.time.Clock
import java.util.UUID
import kotlin.time.toKotlinInstant

/**
 * Builds the fleet read model again from the source data (phase 11, ADMIN), for when it is ever wrong. It reads each
 * context through its public API and applies the same newer-wins rules as the event handlers, so the result equals
 * what the events built (TC-11-PRJ-05). Accounts and trip stages go through the rules of [FleetDriver]. Positions and
 * statuses go through the same statements as the event handlers, [FleetRepository.savePositions] and
 * [FleetRepository.saveDevices], so their rule has one implementation. One transaction: the table is emptied and
 * filled, and the audit row is written, together.
 *
 * The table is emptied first, before reading the sources. Emptying locks it, so an event handler waits until the
 * rebuild commits and then applies its change on top (newer wins). Read first and empty later, and an event delivered
 * in between would be written, then wiped, and never seen by the reads.
 */
class FleetRebuild(
    private val fleet: FleetRepository,
    private val accounts: DriverAccounts,
    private val activity: DriverActivity,
    private val trips: DriverTrips,
    private val audit: AuditLog,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** Returns how many drivers the rebuilt table holds. */
    suspend fun rebuild(actor: Actor): Int {
        FleetService.requireAdmin(actor)
        return transactions.inTransaction {
            fleet.clear()
            val drivers = mutableMapOf<UUID, FleetDriver>()

            fun update(
                id: UUID,
                change: (FleetDriver) -> FleetDriver?,
            ) {
                val current = drivers[id] ?: FleetDriver(id)
                drivers[id] = change(current) ?: current
            }
            accounts.drivers().forEach { account -> update(account.id) { it.withAccount(account.displayName, account.enabled) } }
            trips.latestPerDriver().forEach { trip ->
                val driver = checkNotNull(trip.driverId) { "latestPerDriver returns trips with a driver" }
                update(driver) { it.withTripStage(trip.id, trip.status.name, trip.status.isTerminal, trip.updatedAt) }
            }
            val positions =
                activity.latestPositions().associate {
                    it.driverId to
                        DriverPosition(it.lat, it.lng, it.speedMps, it.bearingDeg, it.recordedAt)
                }
            val devices =
                activity.latestStatuses().associate {
                    it.driverId to
                        DeviceState(
                            it.levelPercent,
                            it.isCharging,
                            it.motion,
                            it.at,
                        )
                }
            val now = clock.instant().toKotlinInstant()
            fleet.insertAll(drivers.values, now)
            fleet.savePositions(positions, now)
            fleet.saveDevices(devices, now)
            val count = (drivers.keys + positions.keys + devices.keys).size
            audit.record(AuditEntry(actor, "FLEET_REBUILT", "FLEET", "fleet_drivers", details = mapOf("drivers" to count.toString())))
            count
        }
    }
}
