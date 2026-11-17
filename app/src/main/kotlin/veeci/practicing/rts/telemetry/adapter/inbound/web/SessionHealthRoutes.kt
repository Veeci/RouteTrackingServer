package veeci.practicing.rts.telemetry.adapter.inbound.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.telemetry.application.SessionHealthService
import veeci.practicing.rts.telemetry.domain.SessionHealthRecord
import java.util.UUID
import kotlin.time.Instant

/**
 * Session health for admins (phase 9b, contract in openapi/rts-v1.yaml). Mount inside `authenticate(AUTH_JWT)`.
 * The service refuses every role but ADMIN.
 */
fun Route.sessionHealthRoutes(service: SessionHealthService) {
    get(PATH) {
        call.respond(SessionHealthResponse.of(service.get(call.sessionId(), call.actor())))
    }

    // Computes the session now, even while it is live, and answers with the new health.
    post(PATH) {
        call.respond(SessionHealthResponse.of(service.recompute(call.sessionId(), call.actor())))
    }
}

private const val PATH = "/api/v1/admin/sessions/{id}/health"

private fun ApplicationCall.sessionId(): UUID =
    parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw RequestInvalidException(listOf(FieldError("id", "must be a UUID")))

@Serializable
data class SessionHealthResponse(
    val sessionId: String,
    val driverId: String? = null,
    val startedAt: Instant,
    val sessionLastSeenAt: Instant,
    val computedAt: Instant,
    val device: DeviceDto? = null,
    val fixCount: Int,
    val rejectedCount: Int,
    val acceptedRatio: Double? = null,
    val rejectionsByReason: Map<String, Int>,
    val intervalP50S: Double? = null,
    val intervalP95S: Double? = null,
    val accuracyP50M: Double? = null,
    val accuracyP95M: Double? = null,
    val gaps: List<GapDto>,
    val batteryDrainPctPerHour: Double? = null,
    val providerMix: Map<String, Double>,
    val primaryProvider: String? = null,
) {
    companion object {
        fun of(record: SessionHealthRecord): SessionHealthResponse {
            val health = record.health
            return SessionHealthResponse(
                sessionId = record.sessionId.toString(),
                driverId = record.driverId?.toString(),
                startedAt = record.startedAt,
                sessionLastSeenAt = record.sessionLastSeenAt,
                computedAt = record.computedAt,
                device = record.device?.let { DeviceDto(it.manufacturer, it.model, it.androidSdk, it.rom, it.hasGms) },
                fixCount = health.fixCount,
                rejectedCount = health.rejectedCount,
                acceptedRatio = health.acceptedRatio,
                rejectionsByReason = health.rejectionsByReason,
                intervalP50S = health.intervalP50S,
                intervalP95S = health.intervalP95S,
                accuracyP50M = health.accuracyP50M,
                accuracyP95M = health.accuracyP95M,
                gaps = health.gaps.map { GapDto(it.start, it.durationS, it.expectedIntervalS, it.likelyDoze) },
                batteryDrainPctPerHour = health.batteryDrainPctPerHour,
                providerMix = health.providerMix,
                primaryProvider = health.primaryProvider,
            )
        }
    }
}

@Serializable
data class DeviceDto(
    val manufacturer: String? = null,
    val model: String? = null,
    val androidSdk: Int? = null,
    val rom: String? = null,
    val hasGms: Boolean? = null,
)

@Serializable
data class GapDto(
    val start: Instant,
    val durationS: Double,
    val expectedIntervalS: Double,
    val likelyDoze: Boolean,
)
