package veeci.practicing.rts.trip.application

import veeci.practicing.rts.platform.audit.AuditEntry
import veeci.practicing.rts.platform.audit.AuditLog
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.trip.application.api.TripSnapshot
import veeci.practicing.rts.trip.application.port.out.TripCursor
import veeci.practicing.rts.trip.application.port.out.TripEventOutbox
import veeci.practicing.rts.trip.application.port.out.TripFilter
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.TripAction
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import java.time.Clock

/**
 * What staff (OPERATOR and ADMIN) do with trips (phase 11d): list every trip, and cancel one with a reason. Each method
 * is one transaction. The trip aggregate decides whether staff may cancel; this service adds the audit row in the same
 * transaction, so a cancel without its audit row cannot commit.
 */
class TripAdminService(
    private val trips: TripRepository,
    private val outbox: TripEventOutbox,
    private val audit: AuditLog,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** Every trip that matches [filter], newest first, one page at a time. */
    suspend fun list(
        actor: Actor,
        filter: TripFilter,
        before: TripCursor?,
        limit: Int,
    ): TripPage {
        requireStaff(actor)
        return transactions.inTransaction {
            val found = trips.findMatching(filter, before, limit + 1) // one more tells whether a next page exists
            val page = found.take(limit)
            TripPage(page.map { it.snapshot() }, page.lastOrNull()?.takeIf { found.size > limit }?.let { TripCursor(it.createdAt, it.id) })
        }
    }

    /**
     * Cancels a trip that has not ended. The guest and the driver learn it with `cancelledBy = OPERATOR`; the
     * [reason] goes only to the audit log, as TRIP_CANCELLED. A trip that has ended gets ILLEGAL_TRANSITION.
     */
    suspend fun cancel(
        id: TripId,
        reason: String,
        actor: Actor,
    ): TripSnapshot {
        requireStaff(actor)
        if (reason.isBlank()) throw DomainException(TripError.REASON_REQUIRED, "Say why the trip is cancelled")
        return transactions.inTransaction {
            val trip = find(id)
            val from = trip.status
            trip.perform(TripAction.CANCEL, actor, clock.now())
            save(trip)
            audit.record(AuditEntry(actor, TRIP_CANCELLED, "TRIP", id.value.toString(), reason.trim(), mapOf("from" to from.name)))
            trip.snapshot(version = trip.version + 1)
        }
    }

    private suspend fun find(id: TripId): Trip = trips.find(id) ?: throw DomainException(TripError.TRIP_NOT_FOUND, "No trip ${id.value}")

    /** Saves the change if nobody saved the trip in between, and puts its events in the outbox. */
    private suspend fun save(trip: Trip) {
        if (!trips.update(trip)) {
            throw DomainException(TripError.CONCURRENT_MODIFICATION, "Someone changed this trip at the same time; read it again")
        }
        trip.pullEvents().forEach { outbox.add(it) }
    }

    private fun requireStaff(actor: Actor) {
        if (!actor.role.isStaff) throw DomainException(TripError.TRIP_ACTION_FORBIDDEN, "Only operators and admins manage all trips")
    }

    companion object {
        const val TRIP_CANCELLED = "TRIP_CANCELLED"
    }
}
