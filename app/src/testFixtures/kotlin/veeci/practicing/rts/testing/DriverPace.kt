package veeci.practicing.rts.testing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The pause a test driver leaves between two fix batches. The server drops a session's messages beyond
 * `ws.messagesPerSecond` a second with `error{RATE_LIMITED}` (config/base.conf, WsSessionRunner). This pace uses half
 * of that limit, so the `command_ack`s the driver sends in between still fit under it.
 *
 * A real SDK sends one batch every few seconds. A rate this close to the limit exists only so that tests finish fast.
 */
val TEST_DRIVER_PACE: Duration = 1.seconds / (TestConfig.load().ws.messagesPerSecond / 2).coerceAtLeast(1)
