package veeci.practicing.rts.platform.http

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.testApp

@Testcontainers(disabledWithoutDocker = true)
class RequestGuardsIT {
    @Serializable
    private data class NewTrip(
        val name: String,
        val seats: Int,
    ) : Validatable {
        override fun validate() =
            buildList {
                if (name.isBlank()) add(FieldError("name", "must not be blank"))
                if (seats !in 1..8) add(FieldError("seats", "must be between 1 and 8"))
            }
    }

    private val routes: Routing.() -> Unit = {
        post("/test/trips") {
            call.receive<NewTrip>()
            call.respond(HttpStatusCode.Created)
        }
    }

    private val tinyBodyLimit = mapOf("HTTP_MAXBODYBYTES" to "64")
    private val oversizedTrip = """{"name": "${"x".repeat(100)}", "seats": 2}"""

    @Test
    fun `a valid body reaches the route`() =
        testApp(routes = routes) {
            client.post("/test/trips") { json("""{"name": "Airport run", "seats": 3}""") }.status shouldBe HttpStatusCode.Created
        }

    @Test
    fun `TC-1-ERR-04 an invalid body becomes a 400 problem listing every invalid field`() =
        testApp(routes = routes) {
            val response = client.post("/test/trips") { json("""{"name": " ", "seats": 0}""") }

            response.status shouldBe HttpStatusCode.BadRequest
            val problem = response.problem()
            problem.code shouldBe "VALIDATION_FAILED"
            problem.errors shouldBe
                listOf(
                    FieldError("name", "must not be blank"),
                    FieldError("seats", "must be between 1 and 8"),
                )
        }

    @Test
    fun `TC-1-ERR-05 a body over the size limit becomes a 413 problem`() =
        testApp(env = tinyBodyLimit, routes = routes) {
            val response = client.post("/test/trips") { json(oversizedTrip) }

            response.status shouldBe HttpStatusCode.PayloadTooLarge
            response.problem().code shouldBe "PAYLOAD_TOO_LARGE"
        }

    @Test
    fun `a streamed body without Content-Length is cut off at the limit too`() =
        testApp(env = tinyBodyLimit, routes = routes) {
            val response =
                client.post("/test/trips") {
                    contentType(ContentType.Application.Json)
                    setBody(ByteReadChannel(oversizedTrip)) // no Content-Length: the size is only known while reading
                }

            response.status shouldBe HttpStatusCode.PayloadTooLarge
        }

    @Test
    fun `a body in a format the server cannot read becomes a 415 problem`() =
        testApp(routes = routes) {
            val response =
                client.post("/test/trips") {
                    contentType(ContentType.Text.Plain)
                    setBody("name=Airport run")
                }

            response.status shouldBe HttpStatusCode.UnsupportedMediaType
            response.problem().code shouldBe "UNSUPPORTED_MEDIA_TYPE"
        }

    @Test
    fun `TC-1-RL-01 exceeding the rate limit becomes a 429 problem with Retry-After`() =
        testApp(env = mapOf("HTTP_RATELIMITPERMINUTE" to "3")) {
            repeat(3) { client.get("/").status shouldBe HttpStatusCode.OK }

            val response = client.get("/")

            response.status shouldBe HttpStatusCode.TooManyRequests
            response.headers[HttpHeaders.RetryAfter].shouldNotBeNull()
            response.problem().code shouldBe "RATE_LIMITED"
        }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
