package veeci.practicing.rts.platform.observability

import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.testApp

@Testcontainers(disabledWithoutDocker = true)
class MetricsIT {
    @Test
    fun `TC-1-OBS-02 metrics expose a timer for the route of the previous request`() =
        testApp {
            client.get("/health/live")

            val metrics = client.get("/metrics").bodyAsText()

            metrics shouldContain "http_server_requests_seconds_count"
            metrics shouldContain """route="/health/live""""
        }

    @Test
    fun `metrics include the JVM and the connection pool`() =
        testApp {
            val metrics = client.get("/metrics").bodyAsText()

            metrics shouldContain "jvm_memory_used_bytes"
            metrics shouldContain """hikaricp_connections_active{pool="rts-db"}"""
        }
}
