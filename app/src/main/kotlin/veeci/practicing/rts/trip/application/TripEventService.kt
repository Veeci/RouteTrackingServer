package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.trip.application.port.out.ArrivalStateStore
import veeci.practicing.rts.trip.application.port.out.TripEventOutbox
import veeci.practicing.rts.trip.application.port.out.TripMomentRepository
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.ArrivalRules
import veeci.practicing.rts.trip.domain.ArrivalSample
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock

/**
 * Notices moments in the driver's stream and raises them: "driver arriving", and auto-arrive (phase 4).
 *
 * Runs in the outbox relay's transaction (TransactionRunner joins it), so the rule state, the stored moments,
 * the status change and the new outbox rows commit together with "this batch is delivered". A batch delivered
 * twice changes nothing the second time: the rules skip fixes at or before the last one they counted.
 */
class TripEventService(
    private val trips: TripRepository,
    private val arrivalStates: ArrivalStateStore,
    private val moments: TripMomentRepository,
    private val outbox: TripEventOutbox,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val rules: ArrivalRules = ArrivalRules(),
) {
    suspend fun onFixesAccepted(event: FixesAccepted) {
        val driver = event.driverId ?: return
        transactions.inTransaction {
            val trip = trips.findActiveForDriver(UserId(driver))
            // Arrival matters only on the way to the pickup.
            if (trip == null || trip.status != TripStatus.ACCEPTED) return@inTransaction
            var state = arrivalStates.lock(trip.id)
            for (position in event.positions) {
                val sample = ArrivalSample(Haversine.distance(position.point, trip.pickup).value, position.speedMps, position.recordedAt)
                val outcome = rules.evaluate(state, sample)
                state = outcome.state
                outcome.arriving?.let {
                    raise(
                        TripMoment.DriverArriving(trip.id.value, trip.guestId.value, trip.status, it.at, it.remainingM),
                    )
                }
                if (outcome.arrived != null) {
                    arrive(trip)
                    break
                }
            }
            arrivalStates.save(trip.id, state)
        }
    }

    private suspend fun raise(moment: TripMoment) {
        moments.append(moment)
        outbox.add(moment)
    }

    private suspend fun arrive(trip: Trip) {
        if (!trip.autoArrive(clock.now())) return
        // Someone changed the trip in between (the driver tapped "arrived"): fail, so the relay rolls everything
        // back and delivers the batch again; the next attempt sees the new stage and does nothing.
        if (!trips.update(trip)) {
            throw DomainException(TripError.CONCURRENT_MODIFICATION, "Trip ${trip.id.value} changed while it was auto-arriving")
        }
        trip.pullEvents().forEach { outbox.add(it) }
    }
}
