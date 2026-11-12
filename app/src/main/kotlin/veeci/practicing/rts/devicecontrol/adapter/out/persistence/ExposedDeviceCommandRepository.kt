package veeci.practicing.rts.devicecontrol.adapter.out.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.json.jsonb
import veeci.practicing.rts.devicecontrol.application.port.out.DeviceCommandRepository
import veeci.practicing.rts.devicecontrol.domain.CommandId
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.devicecontrol.domain.CommandStatus
import veeci.practicing.rts.devicecontrol.domain.DeviceCommand
import veeci.practicing.rts.devicecontrol.domain.GeofenceRegion
import veeci.practicing.rts.devicecontrol.domain.TrackingAccuracy
import veeci.practicing.rts.devicecontrol.domain.kind
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import kotlin.time.Instant

/** The `device_commands` table (migration V10). */
internal object DeviceCommandsTable : Table("device_commands") {
    val id = javaUUID("id")
    val driverId = javaUUID("driver_id")
    val kind = text("kind")
    val payload = jsonb("payload", Json, JsonElement.serializer())
    val status = text("status")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Runs inside the caller's transaction; it opens none of its own. */
class ExposedDeviceCommandRepository : DeviceCommandRepository {
    override suspend fun insert(command: DeviceCommand) {
        DeviceCommandsTable.insert {
            it[id] = command.id.value
            it[driverId] = command.driverId.value
            it[kind] = command.payload.kind()
            it[payload] = Json.encodeToJsonElement(StoredPayload.serializer(), StoredPayload.of(command.payload))
            it[status] = command.status.name
            it[createdAt] = command.createdAt
            it[expiresAt] = command.expiresAt
            it[updatedAt] = command.updatedAt
        }
    }

    override suspend fun find(id: CommandId): DeviceCommand? =
        DeviceCommandsTable
            .selectAll()
            .where { DeviceCommandsTable.id eq id.value }
            .singleOrNull()
            ?.toCommand()

    override suspend fun update(command: DeviceCommand) {
        DeviceCommandsTable.update({ DeviceCommandsTable.id eq command.id.value }) {
            it[status] = command.status.name
            it[updatedAt] = command.updatedAt
        }
    }

    override suspend fun openFor(driver: UserId): List<DeviceCommand> =
        DeviceCommandsTable
            .selectAll()
            .where { (DeviceCommandsTable.driverId eq driver.value) and (DeviceCommandsTable.status inList OPEN) }
            .orderBy(DeviceCommandsTable.createdAt to SortOrder.ASC, DeviceCommandsTable.id to SortOrder.ASC)
            .map { it.toCommand() }

    override suspend fun latestFor(
        driver: UserId,
        limit: Int,
    ): List<DeviceCommand> =
        DeviceCommandsTable
            .selectAll()
            .where { DeviceCommandsTable.driverId eq driver.value }
            .orderBy(DeviceCommandsTable.createdAt to SortOrder.DESC, DeviceCommandsTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toCommand() }

    override suspend fun findDue(
        now: Instant,
        limit: Int,
    ): List<DeviceCommand> =
        DeviceCommandsTable
            .selectAll()
            .where { (DeviceCommandsTable.status inList OPEN) and (DeviceCommandsTable.expiresAt lessEq now) }
            .orderBy(DeviceCommandsTable.expiresAt to SortOrder.ASC)
            .limit(limit)
            .map { it.toCommand() }

    private fun ResultRow.toCommand() =
        DeviceCommand.restore(
            CommandId(this[DeviceCommandsTable.id]),
            UserId(this[DeviceCommandsTable.driverId]),
            Json.decodeFromJsonElement(StoredPayload.serializer(), this[DeviceCommandsTable.payload]).toPayload(),
            this[DeviceCommandsTable.createdAt],
            this[DeviceCommandsTable.expiresAt],
            CommandStatus.valueOf(this[DeviceCommandsTable.status]),
            this[DeviceCommandsTable.updatedAt],
        )

    /** The stored form of a payload. A contract with old rows: fields may be added with defaults. */
    @Serializable
    private sealed interface StoredPayload {
        fun toPayload(): CommandPayload

        @Serializable
        @SerialName("SET_CONSTRAINT")
        data class SetConstraint(
            val maxAccuracy: String,
            val minIntervalMs: Long,
            val reason: String,
        ) : StoredPayload {
            override fun toPayload() = CommandPayload.SetConstraint(TrackingAccuracy.valueOf(maxAccuracy), minIntervalMs, reason)
        }

        @Serializable
        @SerialName("REGISTER_GEOFENCES")
        data class RegisterGeofences(
            val regions: List<Region>,
        ) : StoredPayload {
            override fun toPayload() =
                CommandPayload.RegisterGeofences(regions.map { GeofenceRegion(it.id, GeoPoint(it.lat, it.lng), it.radiusM) })
        }

        @Serializable
        @SerialName("FLUSH_QUEUE")
        data object FlushQueue : StoredPayload {
            override fun toPayload() = CommandPayload.FlushQueue
        }

        @Serializable
        data class Region(
            val id: String,
            val lat: Double,
            val lng: Double,
            val radiusM: Double,
        )

        companion object {
            fun of(payload: CommandPayload): StoredPayload =
                when (payload) {
                    is CommandPayload.SetConstraint -> {
                        SetConstraint(payload.maxAccuracy.name, payload.minIntervalMs, payload.reason)
                    }

                    is CommandPayload.RegisterGeofences -> {
                        RegisterGeofences(payload.regions.map { Region(it.id, it.center.lat, it.center.lng, it.radiusM) })
                    }

                    CommandPayload.FlushQueue -> {
                        FlushQueue
                    }
                }
        }
    }

    private companion object {
        val OPEN = listOf(CommandStatus.PENDING.name, CommandStatus.SENT.name)
    }
}
