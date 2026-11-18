package veeci.practicing.rts.tracking.adapter.inbound.ws

import io.kotest.matchers.shouldBe
import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.platform.ws.WsSession
import veeci.practicing.rts.shared.UserId
import java.lang.reflect.Proxy
import java.util.UUID

class ConnectedDriversTest {
    private val driver = UserId(UUID.randomUUID())

    /** A session the registry only stores. Any call on its socket fails the test. */
    private fun session(): WsSession {
        val socket =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DefaultWebSocketServerSession::class.java)) { _, method, _ ->
                error("the socket is not used here: ${method.name}")
            } as DefaultWebSocketServerSession
        return WsSession(UUID.randomUUID().toString(), "/test", socket, Job())
    }

    @Test
    fun `a connect listener that throws CancellationException while the socket is served does not stop the later ones`() =
        runTest {
            val drivers = ConnectedDrivers()
            val reached = mutableListOf<UserId>()
            drivers.onConnected { throw CancellationException("sent to a closing socket") }
            drivers.onConnected { reached += it }

            drivers.connected(driver, session())

            reached shouldBe listOf(driver)
        }
}
