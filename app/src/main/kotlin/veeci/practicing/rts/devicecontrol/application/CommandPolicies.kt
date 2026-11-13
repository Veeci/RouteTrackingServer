package veeci.practicing.rts.devicecontrol.application

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import veeci.practicing.rts.devicecontrol.application.api.GuestWatching
import veeci.practicing.rts.devicecontrol.application.port.out.DeviceCommandRepository
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.devicecontrol.domain.GeofenceRegion
import veeci.practicing.rts.devicecontrol.domain.TrackingAccuracy
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.event.TripStatusChanged
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

/**
 * The server's own commands (phase 5). No person acts, so they are issued without an actor.
 *
 * | When                                          | Command                                     |
 * |-----------------------------------------------|---------------------------------------------|
 * | The first guest starts watching               | SetConstraint(HIGH, 1000 ms, guest_watching) |
 * | The last guest leaves, ride not started       | SetConstraint(BALANCED, 5000 ms, no_viewers) |
 * | Trip ACCEPTED                                 | RegisterGeofences(pickup, 150 m)            |
 * | Trip IN_PROGRESS                              | RegisterGeofences(destination, 150 m)       |
 * | Trip COMPLETED or CANCELLED                   | SetConstraint(PASSIVE, 60000 ms, trip_ended) |
 *
 * Debounce, so a guest whose connection flaps does not flood the device: a constraint equal to the driver's
 * latest one is never sent again, and "no_viewers" is skipped when any constraint went out less than
 * [debounce] ago. Raising precision for a watching guest and the end of a trip are never delayed.
 */
class CommandPolicies(
    private val commands: CommandService,
    private val repository: DeviceCommandRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val debounce: Duration = 10.seconds,
    private val geofenceRadiusM: Double = GEOFENCE_RADIUS_M,
) : GuestWatching {
    private val log = LoggerFactory.getLogger(CommandPolicies::class.java)

    override suspend fun firstGuestWatching(driver: UserId) = safely { constrain(driver, GUEST_WATCHING) }

    override suspend fun lastGuestLeft(
        driver: UserId,
        rideStarted: Boolean,
    ) = safely { if (!rideStarted) constrain(driver, NO_VIEWERS, debounced = true) }

    /** A trip changed its stage (trip context, through the outbox). */
    suspend fun onTripStatus(event: TripStatusChanged) {
        val driver = event.driverId?.let(::UserId) ?: return
        when (event.to) {
            TripStatus.ACCEPTED -> event.pickup?.let { fence(driver, "pickup", it) }
            TripStatus.IN_PROGRESS -> event.destination?.let { fence(driver, "destination", it) }
            TripStatus.COMPLETED, TripStatus.CANCELLED -> constrain(driver, TRIP_ENDED)
            TripStatus.REQUESTED, TripStatus.ARRIVED -> Unit
        }
    }

    private suspend fun fence(
        driver: UserId,
        id: String,
        center: GeoPoint,
    ) {
        commands.issueAsServer(driver, CommandPayload.RegisterGeofences(listOf(GeofenceRegion(id, center, geofenceRadiusM))))
    }

    private suspend fun constrain(
        driver: UserId,
        constraint: CommandPayload.SetConstraint,
        debounced: Boolean = false,
    ) {
        transactions.inTransaction {
            val latest = repository.latestFor(driver, RECENT).firstOrNull { it.payload is CommandPayload.SetConstraint }
            val same = latest?.payload == constraint
            val tooSoon = debounced && latest != null && now() - latest.createdAt < debounce
            if (!same && !tooSoon) commands.issueAsServer(driver, constraint)
        }
    }

    @Suppress("TooGenericExceptionCaught") // a policy that fails must not break the guest's socket
    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("A device command policy failed; the device keeps its setting", e)
        }
    }

    private fun now() = clock.instant().toKotlinInstant()

    private companion object {
        const val GEOFENCE_RADIUS_M = 150.0
        const val RECENT = 20
        val GUEST_WATCHING = CommandPayload.SetConstraint(TrackingAccuracy.HIGH, 1_000, "guest_watching")
        val NO_VIEWERS = CommandPayload.SetConstraint(TrackingAccuracy.BALANCED, 5_000, "no_viewers")
        val TRIP_ENDED = CommandPayload.SetConstraint(TrackingAccuracy.PASSIVE, 60_000, "trip_ended")
    }
}
