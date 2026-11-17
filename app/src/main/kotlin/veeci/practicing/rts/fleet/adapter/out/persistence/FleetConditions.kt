package veeci.practicing.rts.fleet.adapter.out.persistence

import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import veeci.practicing.rts.fleet.domain.Bbox
import veeci.practicing.rts.fleet.domain.FleetCursor
import veeci.practicing.rts.fleet.domain.FleetFilter
import java.util.UUID
import kotlin.time.Instant

/** The WHERE clauses of the driver list: its filters and its keyset cursor. */
internal object FleetConditions {
    private val table = FleetDriversTable

    /** Exposed joins two LIKE patterns only if they escape with the same character. */
    private const val ESCAPE = '\\'

    fun matching(
        filter: FleetFilter,
        onlineSince: Instant,
    ): Op<Boolean> =
        listOfNotNull(
            filter.online?.let { if (it) table.lastSeenAt greater onlineSince else offline(onlineSince) },
            filter.onTrip?.let { if (it) table.currentTripId.isNotNull() else table.currentTripId.isNull() },
            filter.q?.let(::search),
            filter.bbox?.let(::inside),
        ).fold(Op.TRUE as Op<Boolean>) { all, next -> all and next }

    /** After the cursor in (last_seen_at DESC NULLS LAST, driver_id) order: never-seen drivers come after all others. */
    fun after(cursor: FleetCursor?): Op<Boolean> {
        val seen = cursor?.lastSeenAt
        return when {
            cursor == null -> {
                Op.TRUE
            }

            seen == null -> {
                table.lastSeenAt.isNull() and (table.driverId greater cursor.driverId)
            }

            else -> {
                (table.lastSeenAt less seen) or
                    ((table.lastSeenAt eq seen) and (table.driverId greater cursor.driverId)) or
                    table.lastSeenAt.isNull()
            }
        }
    }

    private fun offline(onlineSince: Instant): Op<Boolean> = table.lastSeenAt.isNull() or (table.lastSeenAt lessEq onlineSince)

    /** Part of the name in any case, with `%` and `_` taken literally; or the whole driver id. */
    private fun search(q: String): Op<Boolean> {
        val name = table.displayName.lowerCase() like (LikePattern("%", ESCAPE) + LikePattern.ofLiteral(q.lowercase(), ESCAPE) + "%")
        val id = runCatching { UUID.fromString(q) }.getOrNull()
        return if (id == null) name else name or (table.driverId eq id)
    }

    /** BETWEEN includes both ends, so a driver on an edge is inside. A driver with no position is outside. */
    private fun inside(bbox: Bbox): Op<Boolean> =
        table.lat.between(bbox.minLat, bbox.maxLat) and table.lng.between(bbox.minLng, bbox.maxLng)
}
