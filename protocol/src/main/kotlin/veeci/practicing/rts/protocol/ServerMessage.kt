package veeci.practicing.rts.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Every message the server sends. Phase 2 adds welcome, ack and the rest. */
@Serializable
sealed interface ServerMessage

/** A recoverable problem with one message. The session stays open; the client may fix and resend. */
@Serializable
@SerialName("error")
data class ErrorMessage(
    /** One of [WsErrorCodes]; clients branch on it. */
    val code: String,
    /** For humans and logs; may change between versions. */
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
}
