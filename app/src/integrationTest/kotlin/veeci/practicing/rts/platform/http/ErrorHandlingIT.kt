package veeci.practicing.rts.platform.http

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode
import veeci.practicing.rts.testing.testApp

@Testcontainers(disabledWithoutDocker = true)
class ErrorHandlingIT {
    /** Stands in for a real context's error enum until the trip context exists. */
    private enum class TestError(
        override val category: ErrorCategory,
    ) : ErrorCode {
        TRIP_NOT_FOUND(ErrorCategory.NOT_FOUND),
    }

    @Serializable
    private data class Payload(
        val name: String,
    )

    private val testRoutes: Routing.() -> Unit = {
        get("/test/bug") { error("secret: db password is hunter2") }
        get("/test/trip") { throw DomainException(TestError.TRIP_NOT_FOUND, "Trip 42 does not exist") }
        post("/test/echo") { call.respond(call.receive<Payload>()) }
    }

    @Test
    fun `TC-1-ERR-01 an unexpected exception becomes a 500 problem that leaks nothing`() =
        testApp(testRoutes) {
            val response = client.get("/test/bug")

            response.status shouldBe HttpStatusCode.InternalServerError
            response.bodyAsText() shouldNotContain "secret"
            val problem = response.problem()
            problem.code shouldBe "INTERNAL_ERROR"
            problem.traceId.shouldNotBeBlank()
        }

    @Test
    fun `TC-1-ERR-02 a domain exception becomes a problem with its code and status`() =
        testApp(testRoutes) {
            val response = client.get("/test/trip")

            response.status shouldBe HttpStatusCode.NotFound
            response.problem() shouldBe
                Problem(
                    type = "urn:rts:error:trip-not-found",
                    title = "Trip not found",
                    status = 404,
                    detail = "Trip 42 does not exist",
                    instance = "/test/trip",
                    code = "TRIP_NOT_FOUND",
                    traceId = response.problem().traceId,
                )
        }

    @Test
    fun `TC-1-ERR-03 a malformed JSON body becomes a 400 problem`() =
        testApp(testRoutes) {
            val response =
                client.post("/test/echo") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name": """)
                }

            response.status shouldBe HttpStatusCode.BadRequest
            response.problem().code shouldBe "MALFORMED_REQUEST"
        }

    @Test
    fun `TC-1-ERR-06 an unknown route becomes a 404 problem`() =
        testApp {
            val response = client.get("/no/such/route")

            response.status shouldBe HttpStatusCode.NotFound
            response.problem().code shouldBe "NOT_FOUND"
        }

    @Test
    fun `a valid request is not touched by error handling`() =
        testApp(testRoutes) {
            val response =
                client.post("/test/echo") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name": "ok"}""")
                }

            response.status shouldBe HttpStatusCode.OK
        }

    /** Asserts the problem media type, then decodes the body. */
    private suspend fun HttpResponse.problem(): Problem {
        contentType()?.withoutParameters() shouldBe ProblemContentType
        return Json.decodeFromString(bodyAsText())
    }
}
