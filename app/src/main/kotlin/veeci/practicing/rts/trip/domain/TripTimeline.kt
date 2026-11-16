package veeci.practicing.rts.trip.domain

import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripStatus
import kotlin.time.Instant

/** One thing that happened on a trip, for a replay (phase 8c). */
sealed interface TimelineEntry {
    val at: Instant

    /** The trip reached [status]. */
    data class StageReached(
        override val at: Instant,
        val status: TripStatus,
    ) : TimelineEntry

    /** The server noticed [moment] (arriving, low battery, stale, back, off route). */
    data class MomentRaised(
        val moment: TripMoment,
    ) : TimelineEntry {
        override val at: Instant get() = moment.at
    }

    /** A route version was planned: the first plan of its kind, or a re-plan after the driver left the route. */
    data class RoutePlanned(
        val route: TripRoute,
    ) : TimelineEntry {
        override val at: Instant get() = route.createdAt
    }
}

object TripTimeline {
    /**
     * The trip's stages, [moments] and [routes] in time order. At the same time a stage comes first, then a moment,
     * then a route, so the order never changes between two calls. Within one source the given order is kept, which
     * is why the sort must be stable (Kotlin's sortedWith is).
     *
     * A stage appears only when its time is stored: a trip saved before V13 shows REQUESTED and nothing else.
     */
    fun of(
        trip: Trip,
        moments: List<TripMoment>,
        routes: List<TripRoute>,
    ): List<TimelineEntry> {
        val entries = stages(trip) + moments.map(TimelineEntry::MomentRaised) + routes.map(TimelineEntry::RoutePlanned)
        return entries.sortedWith(compareBy<TimelineEntry> { it.at }.thenBy(::rank))
    }

    private fun stages(trip: Trip): List<TimelineEntry> {
        val s = trip.stages
        val ended = if (trip.status.isTerminal) trip.status else null
        return listOfNotNull(
            TimelineEntry.StageReached(trip.createdAt, TripStatus.REQUESTED),
            s.acceptedAt?.let { TimelineEntry.StageReached(it, TripStatus.ACCEPTED) },
            s.arrivedAt?.let { TimelineEntry.StageReached(it, TripStatus.ARRIVED) },
            s.startedAt?.let { TimelineEntry.StageReached(it, TripStatus.IN_PROGRESS) },
            ended?.let { status -> s.endedAt?.let { TimelineEntry.StageReached(it, status) } },
        )
    }

    private fun rank(entry: TimelineEntry): Int =
        when (entry) {
            is TimelineEntry.StageReached -> 0
            is TimelineEntry.MomentRaised -> 1
            is TimelineEntry.RoutePlanned -> 2
        }
}
