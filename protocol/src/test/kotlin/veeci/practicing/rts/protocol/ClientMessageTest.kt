package veeci.practicing.rts.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class ClientMessageTest {
    private fun decode(frame: String) = ProtocolJson.decodeFromString(ClientMessage.serializer(), frame)

    private val minimalFix = """{"lat":10.7769,"lng":106.7009,"accuracyM":8.5,"recordedAt":"2026-09-30T02:15:04.120Z"}"""

    @Test
    fun `TC-2-PRO-02 the type field picks the message class`() {
        val message = decode("""{"type":"fix_batch","seq":7,"fixes":[$minimalFix]}""")
        message.shouldBeInstanceOf<FixBatch>().seq shouldBe 7
    }

    @Test
    fun `TC-2-PRO-03 fields added by a newer SDK are ignored`() {
        val frame = """{"type":"hello","protocolVersion":1,"deviceId":"d","sessionId":"s","sdkVersion":"2.0","batteryPct":80}"""

        decode(frame) shouldBe Hello(1, "d", "s", lastAckedSeq = null, sdkVersion = "2.0")
    }

    @Test
    fun `TC-2-PRO-04 a provider this server does not know decodes to UNKNOWN`() {
        val fix = minimalFix.replace("}", ""","provider":"HMS_FUSED"}""")

        val batch = decode("""{"type":"fix_batch","seq":1,"fixes":[$fix]}""") as FixBatch

        batch.fixes.single().provider shouldBe LocationProviderDto.UNKNOWN
    }

    @Test
    fun `TC-2-PRO-06 a fix with only the required fields gets the documented defaults`() {
        val batch = decode("""{"type":"fix_batch","seq":1,"fixes":[$minimalFix]}""") as FixBatch

        batch.fixes.single() shouldBe
            FixDto(
                lat = 10.7769,
                lng = 106.7009,
                accuracyM = 8.5,
                recordedAt = Instant.parse("2026-09-30T02:15:04.120Z"),
                provider = LocationProviderDto.UNKNOWN,
                mock = false,
                satellites = null,
            )
    }

    @Test
    fun `an unknown message type fails decoding, so the socket can answer UNKNOWN_MESSAGE`() {
        shouldThrow<SerializationException> { decode("""{"type":"device_status","at":"2026-09-30T02:15:04Z"}""") }
    }

    @Test
    fun `a missing required field fails decoding`() {
        shouldThrow<SerializationException> { decode("""{"type":"fix_batch","fixes":[$minimalFix]}""") }
    }
}
