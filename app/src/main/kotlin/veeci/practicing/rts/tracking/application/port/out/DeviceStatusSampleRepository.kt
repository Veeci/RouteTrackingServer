package veeci.practicing.rts.tracking.application.port.out

import veeci.practicing.rts.tracking.domain.DeviceStatusSample
import veeci.practicing.rts.tracking.domain.SessionId

interface DeviceStatusSampleRepository {
    /** Stores the sample. A second sample of the same session at the same time is ignored: it is a resend. */
    suspend fun insert(sample: DeviceStatusSample)

    /** The session's samples, oldest first. */
    suspend fun forSession(sessionId: SessionId): List<DeviceStatusSample>
}
