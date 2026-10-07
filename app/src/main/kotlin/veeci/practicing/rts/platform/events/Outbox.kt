package veeci.practicing.rts.platform.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.json.jsonb

/** The `outbox` table (migration V2). */
object OutboxTable : Table("outbox") {
    val id = long("id").autoIncrement()
    val eventType = text("event_type")
    val payload = jsonb("payload", Json, JsonElement.serializer())
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val publishedAt = timestamp("published_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Appends events to the outbox. It must be called inside the caller's transaction (it opens none), so the
 * event commits or rolls back together with the change that caused it.
 */
class OutboxWriter {
    fun append(
        eventType: String,
        payload: JsonElement,
    ) {
        OutboxTable.insert {
            it[OutboxTable.eventType] = eventType
            it[OutboxTable.payload] = payload
        }
    }
}
