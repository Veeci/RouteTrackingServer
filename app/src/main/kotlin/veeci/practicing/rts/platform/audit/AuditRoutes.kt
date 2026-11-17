package veeci.practicing.rts.platform.audit

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.http.FieldError
import veeci.practicing.rts.platform.http.RequestInvalidException
import veeci.practicing.rts.platform.security.actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import java.util.UUID
import kotlin.time.Instant

enum class AuditError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** The audit log is for admins only. */
    AUDIT_FORBIDDEN(ErrorCategory.FORBIDDEN),
}

/**
 * `GET /api/v1/admin/audit` (phase 11, ADMIN): the audit log, newest first, filtered by `actorId`, `action`,
 * `targetType`, `targetId`, `from` and `to`, paged with `cursor` (the last id seen) and `limit` (1 to 100, 50).
 */
fun Route.auditRoutes(
    audit: AuditLog,
    transactions: TransactionRunner,
) {
    get("/api/v1/admin/audit") {
        if (call.actor().role != Role.ADMIN) throw DomainException(AuditError.AUDIT_FORBIDDEN, "The audit log is for admins only")
        val query = AuditQuery.parse(call.parameters)
        val page = transactions.inTransaction { audit.find(query.filter, query.cursor, query.limit + 1) }
        val items = page.take(query.limit)
        call.respond(AuditPage(items.map(AuditRecordDto::of), if (page.size > query.limit) items.last().id.toString() else null))
    }
}

private class AuditQuery(
    val filter: AuditFilter,
    val cursor: Long?,
    val limit: Int,
) {
    companion object {
        private const val DEFAULT_LIMIT = 50
        private val LIMITS = 1..100

        fun parse(parameters: io.ktor.http.Parameters): AuditQuery {
            val errors = mutableListOf<FieldError>()

            fun <T> field(
                name: String,
                parse: (String) -> T,
            ): T? =
                parameters[name]?.let { text ->
                    runCatching { parse(text) }.getOrNull().also { if (it == null) errors += FieldError(name, "is not valid") }
                }
            val actorId = field("actorId") { UserId(UUID.fromString(it)) }
            val from = field("from", Instant::parse)
            val to = field("to", Instant::parse)
            val cursor = field("cursor", String::toLong)
            val limit = field("limit", String::toInt) ?: DEFAULT_LIMIT
            if (limit !in LIMITS) errors += FieldError("limit", "must be 1 to 100")
            if (errors.isNotEmpty()) throw RequestInvalidException(errors)
            val filter = AuditFilter(actorId, parameters["action"], parameters["targetType"], parameters["targetId"], from, to)
            return AuditQuery(filter, cursor, limit)
        }
    }
}

@Serializable
data class AuditPage(
    val items: List<AuditRecordDto>,
    /** Pass as `cursor` for the next, older page; null on the last page. */
    val nextCursor: String? = null,
)

@Serializable
data class AuditRecordDto(
    val id: Long,
    val at: Instant,
    val actorId: String,
    val actorRole: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val reason: String? = null,
    val details: Map<String, String> = emptyMap(),
    val requestId: String? = null,
) {
    companion object {
        fun of(record: AuditRecord) =
            AuditRecordDto(
                record.id,
                record.at,
                record.actorId.value.toString(),
                record.actorRole,
                record.action,
                record.targetType,
                record.targetId,
                record.reason,
                record.details,
                record.requestId,
            )
    }
}
