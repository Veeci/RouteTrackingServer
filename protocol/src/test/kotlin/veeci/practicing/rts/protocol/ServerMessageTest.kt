package veeci.practicing.rts.protocol

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The exact bytes on the wire are a contract with the SDK: these tests fail if they change by accident. */
class ServerMessageTest {
    @Test
    fun `an error encodes with its type tag and without absent fields`() {
        val json = ProtocolJson.encodeToString(ServerMessage.serializer(), ErrorMessage("RATE_LIMITED", "Slow down"))

        json shouldBe """{"type":"error","code":"RATE_LIMITED","message":"Slow down"}"""
    }

    @Test
    fun `an error decodes even when a newer server adds fields`() {
        val json = """{"type":"error","code":"INVALID_MESSAGE","message":"m","correlatesTo":7,"addedLater":true}"""

        ProtocolJson.decodeFromString(ServerMessage.serializer(), json) shouldBe ErrorMessage("INVALID_MESSAGE", "m", 7)
    }
}
