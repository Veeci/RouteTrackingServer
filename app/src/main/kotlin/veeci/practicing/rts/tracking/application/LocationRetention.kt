package veeci.practicing.rts.tracking.application

import veeci.practicing.rts.platform.config.RetentionConfig
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.tracking.application.port.out.Purged
import veeci.practicing.rts.tracking.application.port.out.RetentionRepository
import java.time.Clock
import kotlin.time.toKotlinInstant

/**
 * Deletes raw location data older than [RetentionConfig.rawLocation] (phase 10): the fixes and status samples of
 * sessions last seen before the cutoff. It works by session, not by fix time: `fixes` has no index on its time
 * alone, but its primary key starts with the session, so a purge is a range delete on that key.
 */
class LocationRetention(
    private val retention: RetentionRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val config: RetentionConfig,
) {
    /** Purges at most [RetentionConfig.sessionsPerRun] sessions, in one transaction. Returns what it deleted. */
    suspend fun purgeExpired(): Purged =
        transactions.inTransaction {
            val now = clock.instant().toKotlinInstant()
            retention
                .expiredSessions(now - config.rawLocation, config.sessionsPerRun)
                .fold(Purged.NONE) { total, session -> total + retention.purge(session, now) }
        }
}
