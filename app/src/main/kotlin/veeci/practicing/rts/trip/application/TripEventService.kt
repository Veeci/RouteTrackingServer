package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.Haversine
import veeci.practicing.rts.tracking.application.api.DriverPresence
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReported
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.trip.application.port.out.RuleStateStore
import veeci.practicing.rts.trip.application.port.out.TripEventOutbox
import veeci.practicing.rts.trip.application.port.out.TripMomentRepository
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.ArrivalSample
import veeci.practicing.rts.trip.domain.BatterySample
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripRules
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock

/**
 * Notices moments in the driver's stream and raises them: "driver arriving", auto-arrive, "low battery", and
 * "driver gone" and "driver back" (phase 4).
 *
 * Runs in the outbox relay's transaction (TransactionRunner joins it), so the rule state, the stored moments,
 * the status change and the new outbox rows commit together with "this batch is delivered". A batch delivered
 * twice changes nothing the second time: the rules skip fixes at or before the last one they counted.
 */
class TripEventService(
    private val trips: TripRepository,
    private val states: RuleStateStore,
    private val moments: TripMomentRepository,
    private val outbox: TripEventOutbox,
    private val presence: DriverPresence,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val rules: TripRules = TripRules(),
) {
    suspend fun onFixesAccepted(event: FixesAccepted) {
        val driver = event.driverId ?: return
        transactions.inTransaction {
            val trip = trips.findActiveForDriver(UserId(driver))
            // Arrival matters only on the way to the pickup.
            if (trip == null || trip.status != TripStatus.ACCEPTED) return@inTransaction
            var state = states.lockArrival(trip.id)
            for (position in event.positions) {
                val sample = ArrivalSample(Haversine.distance(position.point, trip.pickup).value, position.speedMps, position.recordedAt)
                val outcome = rules.arrival.evaluate(state, sample)
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
            states.saveArrival(trip.id, state)
        }
    }

    /** The driver's device reported its battery: tell the guest of the driver's active trip when it is low. */
    suspend fun onDeviceStatus(event: DeviceStatusReported) {
        val driver = event.driverId ?: return
        transactions.inTransaction {
            // Any active trip: the guest cares on the way to the pickup and during the ride.
            val trip = trips.findActiveForDriver(UserId(driver)) ?: return@inTransaction
            val battery = event.battery
            val outcome =
                rules.battery.evaluate(
                    states.lockBattery(trip.id),
                    BatterySample(battery.levelPercent, battery.isCharging, battery.profile, event.at),
                )
            outcome.low?.let { raise(TripMoment.DriverLowBattery(trip.id.value, trip.guestId.value, trip.status, it.at, it.levelPercent)) }
            states.saveBattery(trip.id, outcome.state)
        }
    }

    /**
     * Checks every active trip's driver: silent for the stale time raises DriverStale, heard again raises
     * DriverBack. The stale-driver job calls it on one instance at a time. A driver never heard on this trip
     * counts from the trip's last change.
     */
    suspend fun detectStale() {
        transactions.inTransaction {
            val active = trips.findActive()
            val lastSeen = presence.lastSeen(active.mapNotNull { it.driverId })
            val now = clock.now()
            for (trip in active) {
                val seen = trip.driverId?.let(lastSeen::get) ?: trip.updatedAt
                val outcome = rules.presence.evaluate(states.lockPresence(trip.id), seen, now)
                outcome.staleSince?.let { raise(TripMoment.DriverStale(trip.id.value, trip.guestId.value, trip.status, now, it)) }
                if (outcome.back) raise(TripMoment.DriverBack(trip.id.value, trip.guestId.value, trip.status, now))
                states.savePresence(trip.id, outcome.state)
            }
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
