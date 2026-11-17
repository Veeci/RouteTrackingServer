package veeci.practicing.rts.platform.audit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.json.jsonb
import org.slf4j.MDC
import veeci.practicing.rts.platform.observability.REQUEST_ID_MDC
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.UserId
import java.time.Clock
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlinx.serialization.json.Json as KJson

/** One admin action, as the audit log keeps it (phase 11). */
data class AuditEntry(
    val actor: Actor,
    /** What was done, in capitals: DRIVER_DISABLED, COMMAND_ISSUED, ... */
    val action: String,
    /** What it was done to: DRIVER, TRIP, SESSION. */
    val targetType: String,
    val targetId: String,
    /** Why, in the actor's words, when the action asks for one. */
    val reason: String? = null,
    /** A few facts worth keeping, such as a command's kind. Never personal data. */
    val details: Map<String, String> = emptyMap(),
)

data class AuditRecord(
    val id: Long,
    val at: Instant,
    val actorId: UserId,
    val actorRole: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val reason: String?,
    val details: Map<String, String>,
    val requestId: String?,
)

/** Which records to list; every field narrows the list. */
data class AuditFilter(
    val actorId: UserId? = null,
    val action: String? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

/**
 * The audit log (phase 11). [record] joins the caller's transaction, so an action and its record commit or roll
 * back together: an action cannot happen without a record. The table refuses UPDATE, DELETE and TRUNCATE (V19).
 */
interface AuditLog {
    suspend fun record(entry: AuditEntry)

    /** Newest first, after the record with id [before] when given, at most [limit]. */
    suspend fun find(
        filter: AuditFilter,
        before: Long?,
        limit: Int,
    ): List<AuditRecord>
}

/** The `audit_log` table (migration V19). */
internal object AuditLogTable : Table("audit_log") {
    val id = long("id").autoIncrement()
    val at = timestamp("at")
    val actorId = javaUUID("actor_id")
    val actorRole = text("actor_role")
    val action = text("action")
    val targetType = text("target_type")
    val targetId = text("target_id")
    val reason = text("reason").nullable()
    val details = jsonb("details", KJson, JsonObject.serializer())
    val requestId = text("request_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

class ExposedAuditLog(
    private val clock: Clock,
) : AuditLog {
    override suspend fun record(entry: AuditEntry) {
        AuditLogTable.insert {
            it[at] = clock.instant().toKotlinInstant()
            it[actorId] = entry.actor.userId.value
            it[actorRole] = entry.actor.role.name
            it[action] = entry.action
            it[targetType] = entry.targetType
            it[targetId] = entry.targetId
            it[reason] = entry.reason
            it[details] = JsonObject(entry.details.mapValues { (_, value) -> JsonPrimitive(value) })
            // Set by the request logging plugin; the coroutine's MDC context carries it onto the IO thread.
            it[requestId] = MDC.get(REQUEST_ID_MDC)
        }
    }

    override suspend fun find(
        filter: AuditFilter,
        before: Long?,
        limit: Int,
    ): List<AuditRecord> =
        AuditLogTable
            .selectAll()
            .where { conditions(filter, before) }
            .orderBy(AuditLogTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toRecord() }

    private fun conditions(
        filter: AuditFilter,
        before: Long?,
    ): Op<Boolean> =
        listOfNotNull(
            filter.actorId?.let { AuditLogTable.actorId eq it.value },
            filter.action?.let { AuditLogTable.action eq it },
            filter.targetType?.let { AuditLogTable.targetType eq it },
            filter.targetId?.let { AuditLogTable.targetId eq it },
            filter.from?.let { AuditLogTable.at greaterEq it },
            filter.to?.let { AuditLogTable.at less it },
            before?.let { AuditLogTable.id less it },
        ).fold(Op.TRUE as Op<Boolean>) { all, next -> all and next }

    private fun ResultRow.toRecord() =
        AuditRecord(
            id = this[AuditLogTable.id],
            at = this[AuditLogTable.at],
            actorId = UserId(this[AuditLogTable.actorId]),
            actorRole = this[AuditLogTable.actorRole],
            action = this[AuditLogTable.action],
            targetType = this[AuditLogTable.targetType],
            targetId = this[AuditLogTable.targetId],
            reason = this[AuditLogTable.reason],
            details = this[AuditLogTable.details].mapValues { (_, value) -> (value as JsonPrimitive).content },
            requestId = this[AuditLogTable.requestId],
        )
}
