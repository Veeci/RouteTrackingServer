package veeci.practicing.rts.telemetry.adapter.inbound.web

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.telemetry.application.SessionHealthService
import veeci.practicing.rts.telemetry.domain.ProviderGroup
import kotlin.time.Instant

/**
 * The provider comparison report for admins (phase 9c, contract in openapi/rts-v1.yaml), as JSON or CSV. Mount
 * inside `authenticate(AUTH_JWT)`. `?from=&to=` select sessions by their start, `from` included, `to` not.
 */
fun Route.providerReportRoutes(service: SessionHealthService) {
    get("/api/v1/admin/reports/providers") {
        val (from, to) = call.range()
        call.respond(ProviderReportResponse(from, to, service.providerReport(from, to, call.actor()).map(ProviderGroupDto::of)))
    }

    get("/api/v1/admin/reports/providers.csv") {
        val (from, to) = call.range()
        val groups = service.providerReport(from, to, call.actor())
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(
                    ContentDisposition.Parameters.FileName,
                    "providers-${from.toEpochMilliseconds()}-${to.toEpochMilliseconds()}.csv",
                ).toString(),
        )
        call.respondText(ProviderCsv.write(groups), ContentType.parse(ProviderCsv.CONTENT_TYPE).withParameter("charset", "utf-8"))
    }
}

/** `from` and `to` as instants, `to` after `from`, at most 31 days apart; anything else is 400 with field errors. */
private fun ApplicationCall.range(): Pair<Instant, Instant> {
    val errors = mutableListOf<FieldError>()

    fun instant(name: String): Instant? {
        val parsed = parameters[name]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (parsed == null) errors += FieldError(name, "must be an ISO-8601 time, such as 2026-11-01T00:00:00Z")
        return parsed
    }
    val from = instant("from")
    val to = instant("to")
    if (from != null && to != null) {
        if (to <= from) errors += FieldError("to", "must be after from")
        if (to - from > SessionHealthService.MAX_REPORT_RANGE) errors += FieldError("to", "must be at most 31 days after from")
    }
    if (errors.isNotEmpty()) throw RequestInvalidException(errors)
    return checkNotNull(from) to checkNotNull(to)
}

@Serializable
data class ProviderReportResponse(
    val from: Instant,
    val to: Instant,
    val groups: List<ProviderGroupDto>,
)

@Serializable
data class ProviderGroupDto(
    val primaryProvider: String? = null,
    val hasGms: Boolean? = null,
    val rom: String? = null,
    val androidSdk: Int? = null,
    val sessions: Int,
    val lowSample: Boolean,
    val trackedHours: Double,
    val acceptedRatio: Double? = null,
    val intervalP50S: Double? = null,
    val intervalP95S: Double? = null,
    val accuracyP50M: Double? = null,
    val accuracyP95M: Double? = null,
    val batteryDrainPctPerHour: Double? = null,
    val gapsPerHour: Double? = null,
    val dozeGapsPerHour: Double? = null,
) {
    companion object {
        fun of(group: ProviderGroup) =
            ProviderGroupDto(
                group.key.primaryProvider,
                group.key.hasGms,
                group.key.rom,
                group.key.androidSdk,
                group.sessions,
                group.lowSample,
                group.trackedHours,
                group.acceptedRatio,
                group.intervalP50S,
                group.intervalP95S,
                group.accuracyP50M,
                group.accuracyP95M,
                group.batteryDrainPctPerHour,
                group.gapsPerHour,
                group.dozeGapsPerHour,
            )
    }
}
