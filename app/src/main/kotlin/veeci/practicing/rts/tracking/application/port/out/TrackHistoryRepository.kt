package veeci.practicing.rts.tracking.application.port.out

import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.tracking.application.api.TrackCursor
import veeci.practicing.rts.tracking.application.api.TrackPoint
import kotlin.time.Instant

/** Reads stored fixes and batches by driver and time (phase 8). Runs inside the caller's transaction. */
interface TrackHistoryRepository {
    suspend fun fixes(
        driver: UserId,
        from: Instant,
        to: Instant,
        after: TrackCursor?,
        limit: Int,
    ): List<TrackPoint>

    /** The rejection reasons of each batch received in [from, to), one list per batch. */
    suspend fun rejectionReasons(
        driver: UserId,
        from: Instant,
        to: Instant,
    ): List<List<String>>
}
