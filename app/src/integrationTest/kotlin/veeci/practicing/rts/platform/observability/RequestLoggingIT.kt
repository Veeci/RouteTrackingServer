package veeci.practicing.rts.platform.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.slf4j.LoggerFactory
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.testApp

@Testcontainers(disabledWithoutDocker = true)
class RequestLoggingIT {
    @Test
    fun `TC-1-OBS-01 a request without an id gets a generated one in X-Request-Id`() =
        testApp {
            val response = client.get("/health/live")

            response.headers[HttpHeaders.XRequestId].shouldNotBeNull()
        }

    @Test
    fun `TC-1-OBS-01 a caller-supplied request id is kept and echoed`() =
        testApp {
            val response = client.get("/health/live") { header(HttpHeaders.XRequestId, "sdk-7f3a-42") }

            response.headers[HttpHeaders.XRequestId] shouldBe "sdk-7f3a-42"
        }

    @Test
    fun `an unsafe request id is replaced, never copied into logs`() =
        testApp {
            val response = client.get("/health/live") { header(HttpHeaders.XRequestId, "evil id with spaces") }

            response.headers[HttpHeaders.XRequestId] shouldNotBe "evil id with spaces"
        }

    @Test
    fun `a problem's traceId is the request id`() =
        testApp {
            val response = client.get("/no/such/route") { header(HttpHeaders.XRequestId, "trace-me-1") }

            response.problem().traceId shouldBe "trace-me-1"
        }

    @Test
    fun `the request id reaches log lines written inside a transaction on the IO threads`() {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(PROBE_LOGGER) as Logger).addAppender(events)

        val routes: Routing.() -> Unit = {
            get("/test/log") {
                call.application.get<TransactionRunner>().inTransaction {
                    LoggerFactory.getLogger(PROBE_LOGGER).info("inside the transaction")
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
        testApp(routes = routes) {
            client.get("/test/log") { header(HttpHeaders.XRequestId, "req-in-tx") }
        }

        events.list.single().mdcPropertyMap[REQUEST_ID_MDC] shouldBe "req-in-tx"
    }

    private companion object {
        const val PROBE_LOGGER = "probe.RequestLoggingIT"
    }
}
