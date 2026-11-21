package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.trip.application.port.out.TripRepository

/**
 * The driver moved (tracking's FixesAccepted): reads the driver's active trip once and hands it to the two services
 * that react to positions. Two handlers read it each before (fleet lag fix A1). The relay handles one event after
 * another, so every statement it saves per position event raises its ceiling.
 *
 * Arrival goes first, then progress: the order Koin happened to give the two handlers before. Auto-arrive moves the
 * trip from ACCEPTED to ARRIVED in [trip] itself and in the table, so progress follows the stage that arrival left.
 */
class DriverMovementService(
    private val trips: TripRepository,
    private val arrival: TripEventService,
    private val progress: TripProgressService,
    private val transactions: TransactionRunner,
) {
    suspend fun onFixesAccepted(event: FixesAccepted) {
        // No driver: a session from before drivers had accounts. No active trip: nobody is waiting for this driver.
        val driver = event.driverId ?: return
        transactions.inTransaction {
            val trip = trips.findActiveForDriver(UserId(driver)) ?: return@inTransaction
            arrival.onFixesAccepted(event, trip)
            progress.onFixesAccepted(event, trip)
        }
    }
}
