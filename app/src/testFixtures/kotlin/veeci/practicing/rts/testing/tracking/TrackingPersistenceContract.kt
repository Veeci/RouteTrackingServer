package veeci.practicing.rts.testing.tracking

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.testing.tracking.TrackingSamples.T0
import veeci.practicing.rts.testing.tracking.TrackingSamples.bareFix
import veeci.practicing.rts.testing.tracking.TrackingSamples.fullFix
import veeci.practicing.rts.testing.tracking.TrackingSamples.newBatch
import veeci.practicing.rts.tracking.application.port.out.DeviceStatusSampleRepository
import veeci.practicing.rts.tracking.application.port.out.FixBatchRepository
import veeci.practicing.rts.tracking.application.port.out.InsertResult
import veeci.practicing.rts.tracking.application.port.out.StoredBatch
import veeci.practicing.rts.tracking.application.port.out.TrackingSessionRepository
import veeci.practicing.rts.tracking.domain.DeviceInfo
import veeci.practicing.rts.tracking.domain.DeviceStatusSample
import veeci.practicing.rts.tracking.domain.Rejection
import veeci.practicing.rts.tracking.domain.RejectionReason
import veeci.practicing.rts.tracking.domain.TrackingSession
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReported
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the service relies on from the tracking repositories. The in-memory versions (unit tests) and the
 * Postgres versions (integration tests) both extend this class, so both must pass the same tests. That is what
 * makes it safe to test the service against the in-memory versions.
 */
abstract class TrackingPersistenceContract {
    abstract val sessions: TrackingSessionRepository
    abstract val batches: FixBatchRepository
    abstract val statusSamples: DeviceStatusSampleRepository

    /** The database versions run every call in a transaction; the in-memory versions need none. */
    open val transactions: TransactionRunner = DirectTransactionRunner

    protected val session = TrackingSamples.session()

    protected suspend fun <T> tx(block: suspend () -> T): T = transactions.inTransaction(block)

    private suspend fun givenSession() = tx { sessions.save(session) }

    private fun TrackingSession.values() = listOf(id, deviceId, sdkVersion, startedAt, lastSeenAt, driverId, device)

    @Test
    fun `a saved session is found with all its values`() =
        runTest {
            givenSession()

            tx { sessions.find(session.id) }!!.values() shouldBe session.values()
        }

    @Test
    fun `saving a session again updates it`() =
        runTest {
            givenSession()
            session.reconnect(session.deviceId, TrackingSamples.DRIVER, sdkVersion = "1.0.0", at = T0 + 5.minutes)

            tx { sessions.save(session) }

            tx { sessions.find(session.id) }!!.values() shouldBe session.values()
        }

    @Test
    fun `a device comes back with every field, a hello without one keeps it, and none at all reads as null`() =
        runTest {
            givenSession()
            tx { sessions.find(session.id) }!!.device.shouldBeNull()
            val pixel = DeviceInfo("Google", "Pixel 7", 35, "LineageOS 22.1", hasGms = false)

            session.reconnect(session.deviceId, TrackingSamples.DRIVER, "0.10.0", T0 + 1.minutes, pixel)
            tx { sessions.save(session) }
            session.reconnect(session.deviceId, TrackingSamples.DRIVER, "0.10.0", T0 + 2.minutes, device = null)
            tx { sessions.save(session) }

            tx { sessions.find(session.id) }!!.device shouldBe pixel
        }

    @Test
    fun `TC-9-DB-01 status samples come back oldest first with every value, and a resend is ignored`() =
        runTest {
            givenSession()
            val later = DeviceStatusSample(session.id, T0 + 1.minutes, DeviceStatusReported.Battery(14, false, 38.7, "LOW"), "STILL")
            val first = DeviceStatusSample(session.id, T0, DeviceStatusReported.Battery(15, true, null, null), null)

            tx { statusSamples.insert(later) }
            tx { statusSamples.insert(first) }
            tx { statusSamples.insert(later.copy(motion = "IN_VEHICLE")) } // same (session, at): a resend

            tx { statusSamples.forSession(session.id) } shouldBe listOf(first, later)
            tx { statusSamples.forSession(TrackingSamples.session().id) } shouldBe emptyList()
        }

    @Test
    fun `an unknown session is not found`() =
        runTest {
            tx { sessions.find(TrackingSamples.session().id) }.shouldBeNull()
        }

    @Test
    fun `TC-2-DB-02 a stored batch is found with its counts and rejections`() =
        runTest {
            givenSession()
            val rejected = listOf(Rejection(1, RejectionReason.POOR_ACCURACY), Rejection(2, RejectionReason.MOCK_LOCATION))

            tx { batches.insert(newBatch(session.id, 7, accepted = listOf(fullFix(T0)), rejected = rejected)) } shouldBe
                InsertResult.Inserted

            tx { batches.find(session.id, 7) } shouldBe StoredBatch(session.id, 7, T0, acceptedCount = 1, rejected)
        }

    @Test
    fun `a second insert of the same seq writes nothing and returns the first copy`() =
        runTest {
            givenSession()
            tx { batches.insert(newBatch(session.id, 1, accepted = listOf(fullFix(T0)))) }

            val second = tx { batches.insert(newBatch(session.id, 1, accepted = listOf(fullFix(T0 + 1.seconds, cumulativeM = 99.0)))) }

            second shouldBe InsertResult.AlreadyExists(StoredBatch(session.id, 1, T0, acceptedCount = 1, rejected = emptyList()))
            tx { batches.lastAcceptedAtOrBefore(session.id, T0 + 1.minutes) }!!.fix.recordedAt shouldBe T0
        }

    @Test
    fun `the highest seq is null without batches and the maximum otherwise`() =
        runTest {
            givenSession()
            tx { batches.highestSeq(session.id) }.shouldBeNull()

            for (seq in listOf(1L, 3L, 2L)) tx { batches.insert(newBatch(session.id, seq)) }

            tx { batches.highestSeq(session.id) } shouldBe 3
        }

    @Test
    fun `TC-2-DB-04 the last accepted fix at or before a time`() =
        runTest {
            givenSession()
            val other = TrackingSamples.session()
            tx { sessions.save(other) }
            tx { batches.insert(newBatch(session.id, 1, accepted = listOf(fullFix(T0), fullFix(T0 + 10.seconds, index = 1)))) }
            tx { batches.insert(newBatch(other.id, 1, accepted = listOf(fullFix(T0 + 5.seconds)))) }

            tx { batches.lastAcceptedAtOrBefore(session.id, T0 - 1.seconds) }.shouldBeNull()
            tx { batches.lastAcceptedAtOrBefore(session.id, T0 + 5.seconds) }!!.fix.recordedAt shouldBe T0
            tx { batches.lastAcceptedAtOrBefore(session.id, T0 + 10.seconds) }!!.fix.recordedAt shouldBe T0 + 10.seconds
        }

    @Test
    fun `TC-2-DB-05 an accepted fix comes back with every value, times to the millisecond`() =
        runTest {
            givenSession()
            val full = fullFix(T0, index = 0, cumulativeM = 1234.5)
            val bare = bareFix(T0 + 1.seconds, index = 1)
            tx { batches.insert(newBatch(session.id, 1, accepted = listOf(full, bare))) }

            tx { batches.lastAcceptedAtOrBefore(session.id, T0) } shouldBe full
            tx { batches.lastAcceptedAtOrBefore(session.id, T0 + 1.seconds) } shouldBe bare
        }
}
