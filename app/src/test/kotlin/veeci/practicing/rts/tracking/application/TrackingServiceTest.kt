package veeci.practicing.rts.tracking.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.testing.tracking.DirectTransactionRunner
import veeci.practicing.rts.testing.tracking.InMemoryFixBatchRepository
import veeci.practicing.rts.testing.tracking.InMemoryTrackingEventOutbox
import veeci.practicing.rts.testing.tracking.InMemoryTrackingSessionRepository
import veeci.practicing.rts.tracking.application.port.out.FixBatchRepository
import veeci.practicing.rts.tracking.application.port.out.InsertResult
import veeci.practicing.rts.tracking.application.port.out.NewBatch
import veeci.practicing.rts.tracking.application.port.out.StoredBatch
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.FixPipeline
import veeci.practicing.rts.tracking.domain.FixReading
import veeci.practicing.rts.tracking.domain.PipelineConfig
import veeci.practicing.rts.tracking.domain.Readings.NOW
import veeci.practicing.rts.tracking.domain.Readings.START
import veeci.practicing.rts.tracking.domain.Readings.northOf
import veeci.practicing.rts.tracking.domain.Readings.reading
import veeci.practicing.rts.tracking.domain.Rejection
import veeci.practicing.rts.tracking.domain.RejectionReason
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.TrackingError
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

class TrackingServiceTest {
    private val sessions = InMemoryTrackingSessionRepository()
    private val batches = InMemoryFixBatchRepository()
    private val outbox = InMemoryTrackingEventOutbox()

    private val sessionId = SessionId(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"))
    private val phone = DeviceId("phone-1")

    private fun service(
        batches: FixBatchRepository = this.batches,
        now: Instant = NOW,
    ) = TrackingService(
        sessions,
        batches,
        outbox,
        DirectTransactionRunner,
        FixPipeline(PipelineConfig()),
        Clock.fixed(now.toJavaInstant(), ZoneOffset.UTC),
        maxFixesPerBatch = 100,
    )

    private val service = service()

    private suspend fun open(device: DeviceId = phone) = service.openSession(OpenSession(sessionId, device, sdkVersion = "0.9.0"))

    private fun batch(
        seq: Long,
        vararg fixes: FixReading,
    ) = IngestBatch(sessionId, seq, fixes.toList())

    @Test
    fun `TC-2-SES-01 a new session is stored and starts at seq 1`() =
        runTest {
            open().resumeFromSeq shouldBe 1
            sessions.find(sessionId)!!.deviceId shouldBe phone
        }

    @Test
    fun `TC-2-SES-02 a reconnect resumes after the highest stored seq`() =
        runTest {
            open()
            for (seq in 1L..5L) service.ingest(batch(seq, reading(at = NOW - (10 - seq).seconds)))

            open().resumeFromSeq shouldBe 6
        }

    @Test
    fun `a reconnect from another device is refused`() =
        runTest {
            open()

            shouldThrow<DomainException> { open(DeviceId("phone-2")) }.code shouldBe TrackingError.SESSION_DEVICE_MISMATCH
        }

    @Test
    fun `TC-2-ACK-01 a batch is stored, answered with its counts and announced`() =
        runTest {
            open()

            val result = service.ingest(batch(1, reading(at = NOW - 10.seconds), reading(at = NOW, accuracyM = 80.0)))

            result shouldBe BatchResult(1, accepted = 1, rejected = listOf(Rejection(1, RejectionReason.POOR_ACCURACY)), duplicate = false)
            batches.find(sessionId, 1)!!.acceptedCount shouldBe 1
            outbox.events
                .single()
                .positions.size shouldBe 1
        }

    @Test
    fun `TC-2-ACK-02 an empty batch is refused and nothing is stored`() =
        runTest {
            open()

            shouldThrow<DomainException> { service.ingest(batch(1)) }.code shouldBe TrackingError.EMPTY_BATCH
            batches.find(sessionId, 1).shouldBeNull()
        }

    @Test
    fun `TC-2-ACK-03 a resent seq gets the original counts and is not stored again`() =
        runTest {
            open()
            val first = service.ingest(batch(1, reading(at = NOW - 10.seconds), reading(at = NOW, accuracyM = 80.0)))

            // Different content on purpose: the server keeps the first copy and does not compare.
            val again = service.ingest(batch(1, reading(at = NOW - 10.seconds)))

            again shouldBe first.copy(duplicate = true)
            batches.stored.size shouldBe 1
            outbox.events.size shouldBe 1
        }

    @Test
    fun `TC-2-ACK-04 losing an insert race is answered like a resend`() =
        runTest {
            open()
            val stored = service.ingest(batch(1, reading()))
            // find() misses, as if another request stored seq 1 between our find and our insert.
            val racing =
                service(
                    batches =
                        object : FixBatchRepository by batches {
                            override suspend fun find(
                                sessionId: SessionId,
                                seq: Long,
                            ): StoredBatch? = null
                        },
                )

            racing.ingest(batch(1, reading())) shouldBe stored.copy(duplicate = true)
            outbox.events.size shouldBe 1
        }

    @Test
    fun `TC-2-ACK-05 a batch over the limit is refused`() =
        runTest {
            open()
            val fixes = (1..101).map { reading(at = NOW - it.seconds) }

            shouldThrow<DomainException> { service.ingest(IngestBatch(sessionId, 1, fixes)) }.code shouldBe TrackingError.BATCH_TOO_LARGE
        }

    @Test
    fun `TC-2-ACK-06 seqs may arrive out of order`() =
        runTest {
            open()

            for (seq in listOf(1L, 3L, 2L)) service.ingest(batch(seq, reading(at = NOW - (10 - seq).seconds)))

            batches.highestSeq(sessionId) shouldBe 3
            batches.stored.map { it.seq }.toSet() shouldBe setOf(1L, 2L, 3L)
        }

    @Test
    fun `TC-2-ACK-07 a failing repository fails the call and announces nothing`() =
        runTest {
            open()
            val broken =
                service(
                    batches =
                        object : FixBatchRepository by batches {
                            override suspend fun insert(batch: NewBatch): InsertResult = error("disk full")
                        },
                )

            shouldThrow<IllegalStateException> { broken.ingest(batch(1, reading())) }
            outbox.events.shouldBeEmpty()
        }

    @Test
    fun `seq 0 is INVALID_SEQ`() =
        runTest {
            open()

            shouldThrow<DomainException> { service.ingest(batch(0, reading())) }.code shouldBe TrackingError.INVALID_SEQ
        }

    @Test
    fun `a batch for a session that was never opened is SESSION_NOT_FOUND`() =
        runTest {
            shouldThrow<DomainException> { service.ingest(batch(1, reading())) }.code shouldBe TrackingError.SESSION_NOT_FOUND
        }

    @Test
    fun `the next batch continues the running total of the batch before it`() =
        runTest {
            open()
            service.ingest(batch(1, reading(at = NOW - 20.seconds), reading(at = NOW - 10.seconds, point = northOf(START, 100.0))))

            service.ingest(batch(2, reading(at = NOW, point = northOf(START, 200.0))))

            outbox.events
                .last()
                .positions
                .single()
                .cumulativeM shouldBe (200.0 plusOrMinus 0.01)
        }

    @Test
    fun `a batch whose fixes are all rejected is stored and answered, but announces nothing`() =
        runTest {
            open()

            service.ingest(batch(1, reading(accuracyM = 80.0))).accepted shouldBe 0

            batches.find(sessionId, 1)!!.rejected shouldBe listOf(Rejection(0, RejectionReason.POOR_ACCURACY))
            outbox.events.shouldBeEmpty()
        }

    @Test
    fun `a stored batch updates when the device was last seen`() =
        runTest {
            service(now = NOW - 5.minutes).openSession(OpenSession(sessionId, phone, sdkVersion = "0.9.0"))

            service.ingest(batch(1, reading()))

            sessions.find(sessionId)!!.lastSeenAt shouldBe NOW
        }
}
