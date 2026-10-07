package veeci.practicing.rts.tracking.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.tracking.domain.Readings.NOW
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class TrackingSessionTest {
    private val phone = DeviceId("phone-1")

    private fun session() = TrackingSession(SessionId(UUID.randomUUID()), phone, sdkVersion = "0.9.0", startedAt = NOW)

    @Test
    fun `only the device that started a session may continue it`() {
        val error = shouldThrow<DomainException> { session().reconnect(DeviceId("phone-2"), "0.9.0", NOW + 1.minutes) }

        error.code shouldBe TrackingError.SESSION_DEVICE_MISMATCH
    }

    @Test
    fun `a reconnect records the new SDK version and the time`() {
        val session = session()

        session.reconnect(phone, sdkVersion = "1.0.0", at = NOW + 5.minutes)

        session.sdkVersion shouldBe "1.0.0"
        session.lastSeenAt shouldBe NOW + 5.minutes
    }

    @Test
    fun `last seen never moves backwards`() {
        val session = session()

        session.touch(NOW + 5.minutes)
        session.touch(NOW + 1.minutes)

        session.lastSeenAt shouldBe NOW + 5.minutes
    }

    @Test
    fun `the client resumes after the highest stored seq`() {
        session().resumeFrom(highestStoredSeq = null) shouldBe 1
        session().resumeFrom(highestStoredSeq = 5) shouldBe 6
    }

    @Test
    fun `a blank device id is refused`() {
        shouldThrow<IllegalArgumentException> { DeviceId(" ") }
    }
}
