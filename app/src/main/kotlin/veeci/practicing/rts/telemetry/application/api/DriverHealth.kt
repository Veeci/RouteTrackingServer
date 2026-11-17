package veeci.practicing.rts.telemetry.application.api

import java.util.UUID
import kotlin.time.Instant

/** Session health as other contexts see it (phase 11: the fleet's driver detail). Joins the caller's transaction. */
interface DriverHealth {
    /** The computed health of the driver's most recently started session; null when none is computed yet. */
    suspend fun latestFor(driver: UUID): HealthOverview?
}

/** The headline numbers of one session's health. */
data class HealthOverview(
    val sessionId: UUID,
    val startedAt: Instant,
    val computedAt: Instant,
    val fixCount: Int,
    val acceptedRatio: Double?,
    val intervalP50S: Double?,
    val accuracyP50M: Double?,
    val gapCount: Int,
    val batteryDrainPctPerHour: Double?,
    val primaryProvider: String?,
)
