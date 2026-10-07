package veeci.practicing.rts.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("hello")
data class Hello(
    val protocolVersion: Int,
    val deviceId: String,
    val sessionId: String,
    val lastAckedSeq: Long? = null,
    val sdkVersion: String,
) : ClientMessage

@Serializable
@SerialName("fix_batch")
data class FixBatch(
    val seq: Long,
    val fixes: List<FixDto>,
) : ClientMessage
