package veeci.practicing.rts.platform.ws

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import veeci.practicing.rts.platform.config.WsConfig
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.protocol.WsErrorCodes
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** MDC keys put on every log line written during a WebSocket session. */
const val WS_SESSION_MDC = "wsSession"
const val WS_ENDPOINT_MDC = "wsEndpoint"

/**
 * Runs one WebSocket session so every endpoint gets the same behaviour: frames decoded with [ProtocolJson],
 * per-session rate limiting, `error` frames for bad messages (the session stays open), UNKNOWN_MESSAGE for a
 * `type` the endpoint does not know, close 1011 when a
 * handler has a bug, session id in the logs, and one log line when the session opens and when it closes.
 * Endpoints only write the handler for a decoded message.
 */
class WsSessionRunner(
    private val config: WsConfig,
    private val sessions: WsSessionRegistry,
) {
    private val log = LoggerFactory.getLogger(WsSessionRunner::class.java)

    suspend fun <In> run(
        socket: DefaultWebSocketServerSession,
        endpoint: String,
        inbound: DeserializationStrategy<In>,
        handle: suspend WsSession.(In) -> Unit,
    ) {
        val session = WsSession(UUID.randomUUID().toString(), endpoint, socket)
        val mdc = MDC.getCopyOfContextMap().orEmpty() + mapOf(WS_SESSION_MDC to session.id, WS_ENDPOINT_MDC to endpoint)
        withContext(MDCContext(mdc)) {
            val opened = TimeSource.Monotonic.markNow()
            val stats = Stats()
            var outcome = "cancelled"
            sessions.add(session)
            log.info("WebSocket session opened")
            try {
                outcome = serve(socket, session, inbound, handle, stats)
            } finally {
                sessions.remove(session)
                log.info("WebSocket session closed ({}) after {}, {} message(s) received", outcome, opened.elapsedNow(), stats.received)
            }
        }
    }

    /** Returns a short description of how the session ended. */
    @Suppress("TooGenericExceptionCaught") // any transport failure (reset, frame too big) just ends this session
    private suspend fun <In> serve(
        socket: DefaultWebSocketServerSession,
        session: WsSession,
        inbound: DeserializationStrategy<In>,
        handle: suspend WsSession.(In) -> Unit,
        stats: Stats,
    ): String =
        try {
            receive(socket, session, inbound, handle, stats)
                ?: withTimeoutOrNull(CLOSE_REASON_WAIT) { socket.closeReason.await() }?.let { "${it.code} ${it.message}" }
                ?: "connection lost"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "connection failed: ${e::class.simpleName}"
        }

    /** Reads frames until the client goes away (null) or a handler fails (its outcome). */
    private suspend fun <In> receive(
        socket: DefaultWebSocketServerSession,
        session: WsSession,
        inbound: DeserializationStrategy<In>,
        handle: suspend WsSession.(In) -> Unit,
        stats: Stats,
    ): String? {
        val limiter = TokenBucket(config.messagesPerSecond, config.messagesPerSecond)
        val knownTypes = inbound.knownTypes()
        for (frame in socket.incoming) {
            stats.received++
            val text = (frame as? Frame.Text)?.readText()
            when {
                text == null -> {
                    session.sendError(WsErrorCodes.INVALID_MESSAGE, "Only JSON text frames are accepted.")
                }

                !limiter.tryTake() -> {
                    session.sendError(
                        WsErrorCodes.RATE_LIMITED,
                        "More than ${config.messagesPerSecond} messages per second; this one was dropped.",
                    )
                }

                else -> {
                    val message = decode(text, inbound, knownTypes, session) ?: continue
                    if (!handleSafely(session, message, handle)) return "handler failed, closed with 1011"
                }
            }
        }
        return null
    }

    /** Null (after telling the client why) when [text] is not a valid message for this endpoint. */
    private suspend fun <In> decode(
        text: String,
        inbound: DeserializationStrategy<In>,
        knownTypes: Set<String>?,
        session: WsSession,
    ): In? {
        val json = parse(text, session) ?: return null
        val type = json.typeField()
        return if (knownTypes != null && type != null && type !in knownTypes) {
            // A newer client may send a message this server does not know yet. It is told so, and nothing breaks.
            session.sendError(WsErrorCodes.UNKNOWN_MESSAGE, "Unknown message type '$type'.")
            null
        } else {
            decodeShape(json, inbound, session)
        }
    }

    private suspend fun parse(
        text: String,
        session: WsSession,
    ): JsonElement? =
        try {
            ProtocolJson.parseToJsonElement(text)
        } catch (_: SerializationException) {
            session.sendError(WsErrorCodes.MALFORMED_MESSAGE, "The frame is not valid JSON.")
            null
        }

    // kotlinx.serialization reports a wrong shape with either of these two exceptions.
    private suspend fun <In> decodeShape(
        json: JsonElement,
        inbound: DeserializationStrategy<In>,
        session: WsSession,
    ): In? =
        try {
            ProtocolJson.decodeFromJsonElement(inbound, json)
        } catch (_: SerializationException) {
            session.sendError(WsErrorCodes.INVALID_MESSAGE, "Not a message this endpoint accepts.")
            null
        } catch (_: IllegalArgumentException) {
            session.sendError(WsErrorCodes.INVALID_MESSAGE, "Not a message this endpoint accepts.")
            null
        }

    /** A bug in one handler ends that session only, with 1011, never the server. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <In> handleSafely(
        session: WsSession,
        message: In,
        handle: suspend WsSession.(In) -> Unit,
    ): Boolean =
        try {
            session.handle(message)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("WebSocket handler failed; closing the session with 1011", e)
            session.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Internal error"))
            false
        }

    /** The `type` names of a sealed message family, or null when [inbound] is not one (the echo endpoint). */
    @OptIn(ExperimentalSerializationApi::class)
    private fun DeserializationStrategy<*>.knownTypes(): Set<String>? =
        descriptor
            .takeIf { it.kind == PolymorphicKind.SEALED }
            ?.getElementDescriptor(1)
            ?.elementNames
            ?.toSet()

    private fun JsonElement.typeField(): String? = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull

    private class Stats {
        var received = 0
    }

    private companion object {
        val CLOSE_REASON_WAIT = 1.seconds
    }
}
