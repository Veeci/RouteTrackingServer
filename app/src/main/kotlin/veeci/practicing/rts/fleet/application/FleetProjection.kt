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
 * on whichever instance holds the delivery lock, so it keeps no state in memory: the table is the state. Each change
 * reads the row with a lock, applies the newer-wins rule of [FleetDriver], and writes only when something changed.
 */
class FleetProjection(
    private val fleet: FleetRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend fun onPosition(
        driverId: UUID,
        position: DriverPosition,
    ) = apply(driverId) { it.withPosition(position) }

    suspend fun onDeviceStatus(
        driverId: UUID,
        device: DeviceState,
    ) = apply(driverId) { it.withDevice(device) }

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
        transactions.inTransaction {
            val current = fleet.findForUpdate(driverId) ?: FleetDriver(driverId)
            change(current)?.let { fleet.save(it, clock.instant().toKotlinInstant()) }
        }
    }
}
