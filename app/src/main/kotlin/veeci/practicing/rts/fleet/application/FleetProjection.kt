package veeci.practicing.rts.fleet.application

import veeci.practicing.rts.fleet.application.port.out.FleetRepository
import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.platform.db.TransactionRunner
import java.time.Clock
import java.util.UUID
import kotlin.time.toKotlinInstant

/**
 * Keeps the fleet read model up to date from events (phase 11). The outbox relay calls it inside its transaction,
 * on whichever instance holds the delivery lock, so it keeps no state in memory: the table is the state.
 *
 * Positions and device statuses are most of the events, and the relay handles one event after another, so each one
 * costs one statement: [FleetRepository.savePositions] applies newer wins in SQL (fleet lag fix A1). A trip stage or
 * an account reads the row with a lock, applies the rule of [FleetDriver], and writes only when something changed.
 */
class FleetProjection(
    private val fleet: FleetRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend fun onPosition(
        driverId: UUID,
        position: DriverPosition,
    ) = transactions.inTransaction { fleet.savePositions(mapOf(driverId to position), now()) }

    suspend fun onDeviceStatus(
        driverId: UUID,
        device: DeviceState,
    ) = transactions.inTransaction { fleet.saveDevices(mapOf(driverId to device), now()) }

    suspend fun onTripStage(
        driverId: UUID,
        tripId: UUID,
        status: String,
        ended: Boolean,
        at: kotlin.time.Instant,
    ) = apply(driverId) { it.withTripStage(tripId, status, ended, at) }

    suspend fun onAccount(
        driverId: UUID,
        displayName: String,
        enabled: Boolean,
    ) = apply(driverId) { it.withAccount(displayName, enabled) }

    private suspend fun apply(
        driverId: UUID,
        change: (FleetDriver) -> FleetDriver?,
    ) {
        transactions.inTransaction { fleet.update(driverId, now(), change) }
    }

    private fun now() = clock.instant().toKotlinInstant()
}
