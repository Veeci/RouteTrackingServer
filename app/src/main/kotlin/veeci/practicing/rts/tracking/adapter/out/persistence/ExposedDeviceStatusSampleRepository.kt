package veeci.practicing.rts.tracking.adapter.out.persistence

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import veeci.practicing.rts.tracking.application.port.out.DeviceStatusSampleRepository
import veeci.practicing.rts.tracking.domain.DeviceStatusSample
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReported

/** Runs inside the caller's transaction (see TransactionRunner); it opens none of its own. */
class ExposedDeviceStatusSampleRepository : DeviceStatusSampleRepository {
    override suspend fun insert(sample: DeviceStatusSample) {
        // ON CONFLICT DO NOTHING on (session_id, at): a resent sample is not an error.
        DeviceStatusSamplesTable.insertIgnore {
            it[sessionId] = sample.sessionId.value
            it[at] = sample.at
            it[levelPercent] = sample.battery.levelPercent.toShort()
            it[isCharging] = sample.battery.isCharging
            it[temperatureC] = sample.battery.temperatureC
            it[profile] = sample.battery.profile
            it[motion] = sample.motion
        }
    }

    override suspend fun forSession(sessionId: SessionId): List<DeviceStatusSample> =
        DeviceStatusSamplesTable
            .selectAll()
            .where { DeviceStatusSamplesTable.sessionId eq sessionId.value }
            .orderBy(DeviceStatusSamplesTable.at to SortOrder.ASC)
            .map {
                DeviceStatusSample(
                    SessionId(it[DeviceStatusSamplesTable.sessionId]),
                    it[DeviceStatusSamplesTable.at],
                    DeviceStatusReported.Battery(
                        it[DeviceStatusSamplesTable.levelPercent].toInt(),
                        it[DeviceStatusSamplesTable.isCharging],
                        it[DeviceStatusSamplesTable.temperatureC],
                        it[DeviceStatusSamplesTable.profile],
                    ),
                    it[DeviceStatusSamplesTable.motion],
                )
            }
}
