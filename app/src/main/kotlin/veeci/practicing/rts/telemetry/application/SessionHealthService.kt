package veeci.practicing.rts.telemetry.application

import veeci.practicing.rts.devicecontrol.application.api.AppliedConstraints
import veeci.practicing.rts.platform.audit.AuditEntry
import veeci.practicing.rts.platform.audit.AuditLog
import veeci.practicing.rts.platform.config.TelemetryConfig
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.telemetry.application.api.DriverHealth
import veeci.practicing.rts.telemetry.application.api.HealthOverview
import veeci.practicing.rts.telemetry.application.port.out.SessionHealthRepository
import veeci.practicing.rts.telemetry.domain.ConstraintChange
import veeci.practicing.rts.telemetry.domain.DeviceKey
import veeci.practicing.rts.telemetry.domain.HealthFix
import veeci.practicing.rts.telemetry.domain.HealthInput
import veeci.practicing.rts.telemetry.domain.HealthSample
import veeci.practicing.rts.telemetry.domain.ProviderComparison
import veeci.practicing.rts.telemetry.domain.ProviderGroup
import veeci.practicing.rts.telemetry.domain.SessionHealthCalculator
import veeci.practicing.rts.telemetry.domain.SessionHealthRecord
import veeci.practicing.rts.telemetry.domain.TelemetryError
import veeci.practicing.rts.tracking.application.api.SessionTelemetry
import veeci.practicing.rts.tracking.application.api.TelemetrySession
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Computes and stores session health (phase 9). The job calls [computeIdle]; admins read with [get] and compute
 * again with [recompute].
 */
class SessionHealthService(
    private val sessions: SessionTelemetry,
    private val constraints: AppliedConstraints,
    private val health: SessionHealthRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val config: TelemetryConfig,
    private val audit: AuditLog,
) : DriverHealth {
    /**
     * Computes the next sessions that went idle since the last run, at most [TelemetryConfig.batchSize], and moves
     * the watermark past them, all in one transaction: a failed run computes the same sessions again next time.
     * A session that resumes moves its last-seen time past the watermark, so it is computed again once idle.
     */
    suspend fun computeIdle(): Int =
        transactions.inTransaction {
            val idle = sessions.idleSessions(health.watermark(), now() - config.idleAfter, config.batchSize)
            idle.forEach { mark -> sessions.session(mark.sessionId)?.let { health.save(compute(it)) } }
            idle.lastOrNull()?.let { health.moveWatermark(it) }
            idle.size
        }

    suspend fun get(
        sessionId: UUID,
        actor: Actor,
    ): SessionHealthRecord {
        requireAdmin(actor)
        return transactions.inTransaction { health.find(sessionId) } ?: notFound(sessionId)
    }

    /** The provider report over sessions that started in [from, to). */
    suspend fun providerReport(
        from: Instant,
        to: Instant,
        actor: Actor,
    ): List<ProviderGroup> {
        requireAdmin(actor)
        require(from < to && to - from <= MAX_REPORT_RANGE) { "from must be before to, at most $MAX_REPORT_RANGE apart" }
        return ProviderComparison.of(transactions.inTransaction { health.startedBetween(from, to) })
    }

    /** Computes the session now, even if it is still live, and replaces the stored health. The watermark stays. */
    suspend fun recompute(
        sessionId: UUID,
        actor: Actor,
    ): SessionHealthRecord {
        requireAdmin(actor)
        return transactions.inTransaction {
            val session = sessions.session(sessionId) ?: notFound(sessionId)
            compute(session).also {
                health.save(it)
                audit.record(AuditEntry(actor, "SESSION_HEALTH_RECOMPUTED", "SESSION", sessionId.toString()))
            }
        }
    }

    override suspend fun latestFor(driver: UUID): HealthOverview? =
        transactions.inTransaction { health.latestForDriver(driver) }?.let {
            HealthOverview(
                sessionId = it.sessionId,
                startedAt = it.startedAt,
                computedAt = it.computedAt,
                fixCount = it.health.fixCount,
                acceptedRatio = it.health.acceptedRatio,
                intervalP50S = it.health.intervalP50S,
                accuracyP50M = it.health.accuracyP50M,
                gapCount = it.health.gaps.size,
                batteryDrainPctPerHour = it.health.batteryDrainPctPerHour,
                primaryProvider = it.health.primaryProvider,
            )
        }

    private suspend fun compute(session: TelemetrySession): SessionHealthRecord {
        val applied = session.driverId?.let { constraints.history(UserId(it), session.startedAt, session.lastSeenAt) }.orEmpty()
        val input =
            HealthInput(
                fixes = sessions.fixes(session.id).map { HealthFix(it.recordedAt, it.accuracyM, it.provider) },
                rejections = sessions.rejections(session.id),
                samples = sessions.statusSamples(session.id).map { HealthSample(it.at, it.levelPercent, it.isCharging, it.motion) },
                constraints = applied.map { ConstraintChange(it.appliedAt, it.minIntervalMs) },
                defaultInterval = config.defaultInterval,
            )
        return SessionHealthRecord(
            sessionId = session.id,
            driverId = session.driverId,
            startedAt = session.startedAt,
            sessionLastSeenAt = session.lastSeenAt,
            device = session.device?.let { DeviceKey(it.manufacturer, it.model, it.androidSdk, it.rom, it.hasGms) },
            computedAt = now(),
            health = SessionHealthCalculator.calculate(input),
        )
    }

    private fun requireAdmin(actor: Actor) {
        if (actor.role !=
            Role.ADMIN
        ) {
            throw DomainException(TelemetryError.SESSION_HEALTH_FORBIDDEN, "Session health and reports are for admins only")
        }
    }

    private fun notFound(sessionId: UUID): Nothing =
        throw DomainException(TelemetryError.SESSION_HEALTH_NOT_FOUND, "No health for session $sessionId")

    private fun now(): Instant = clock.instant().toKotlinInstant()

    companion object {
        /** The longest report range. A month of sessions is a few thousand rows, grouped in memory. */
        val MAX_REPORT_RANGE = 31.days
    }
}
