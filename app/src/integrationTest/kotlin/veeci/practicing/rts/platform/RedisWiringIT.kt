package veeci.practicing.rts.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.application
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.live.adapter.out.bus.RedisLiveUpdateBus
import veeci.practicing.rts.live.application.port.out.LiveUpdateBus
import veeci.practicing.rts.testing.TestRedis
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.tracking.adapter.out.redis.RedisDriverLink
import veeci.practicing.rts.tracking.application.api.DriverLink

/** With `REDIS_URL` set, the app uses the Redis adapters and reports Redis in readiness (phase 10a). */
@Testcontainers(disabledWithoutDocker = true)
class RedisWiringIT {
    @Test
    fun `with REDIS_URL the live bus and the driver link go through Redis, and readiness shows it`() {
        var bus: LiveUpdateBus? = null
        var link: DriverLink? = null
        testApp(
            env = TestRedis.env(),
            routes = {
                bus = application.get<LiveUpdateBus>()
                link = application.get<DriverLink>()
            },
        ) {
            (bus is RedisLiveUpdateBus) shouldBe true
            (link is RedisDriverLink) shouldBe true

            client.get("/health/ready").apply {
                status shouldBe HttpStatusCode.OK
                bodyAsText() shouldContain "\"redis\":\"UP\""
            }
        }
    }
}
