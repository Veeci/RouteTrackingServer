package veeci.practicing.rts.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("welcome")
data class Welcome(
    val sessionId: String,
    val resumeFromSeq: Long,
    val serverTime: Instant,
    val limits: LimitsDto,
) : ServerMessage

@Serializable
data class LimitsDto(
    val maxFixesPerBatch: Int,
    val maxFrameBytes: Long,
    val maxMessagesPerSecond: Int,
)

@Serializable
@SerialName("ack")
data class Ack(
    val seq: Long,
    val accepted: Int,
    val rejected: List<RejectionDto>,
) : ServerMessage

@Serializable
data class RejectionDto(
    val index: Int,
    val reason: String,
)

@Serializable
@SerialName("error")
data class ErrorMessage(
    val code: String,
    val message: String,
    /** The `seq` of the client message this is about, when it has one. */
    val correlatesTo: Long? = null,
) : ServerMessage

/** Codes carried by [ErrorMessage]. Public API: never renamed once released. */
object WsErrorCodes {
    /** The frame is not valid JSON. */
    const val MALFORMED_MESSAGE = "MALFORMED_MESSAGE"

    /** Valid JSON, but not a message this endpoint accepts (wrong shape or missing fields). */
    const val INVALID_MESSAGE = "INVALID_MESSAGE"

    /** More messages per second than allowed; this one was dropped. */
    const val RATE_LIMITED = "RATE_LIMITED"

    /** Valid JSON with a `type` this endpoint does not know, for example from a newer client. */
    const val UNKNOWN_MESSAGE = "UNKNOWN_MESSAGE"
}

object WsCloseCodes {
    const val UNSUPPORTED_VERSION: Short = 4400
    const val HANDSHAKE_TIMEOUT: Short = 4401
}
