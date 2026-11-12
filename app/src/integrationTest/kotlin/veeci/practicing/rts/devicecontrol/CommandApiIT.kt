package veeci.practicing.rts.devicecontrol

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp

/** The admin command API end to end, every response checked against openapi/rts-v1.yaml. */
@Testcontainers(disabledWithoutDocker = true)
class CommandApiIT {
    private val driverId = TestTokens.newUser()
    private val admin = TestTokens.admin()
    private val path = "/api/v1/admin/drivers/${driverId.value}/commands"
    private val highAccuracy = """{"kind":"SET_CONSTRAINT","maxAccuracy":"HIGH","minIntervalMs":1000,"reason":"support"}"""

    private suspend fun HttpClient.issue(
        body: String,
        token: String?,
    ): HttpResponse =
        post(path) {
            token?.let(::bearerAuth)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.also { it.shouldMatchContract() }

    @Test
    fun `an admin sends a command and reads it back as PENDING`() =
        testApp {
            val issued = client.issue(highAccuracy, admin)
            issued.status shouldBe HttpStatusCode.Accepted
            val commandId =
                Json
                    .parseToJsonElement(issued.bodyAsText())
                    .jsonObject
                    .getValue("commandId")
                    .jsonPrimitive.content

            val listed = client.get(path) { bearerAuth(admin) }.also { it.shouldMatchContract() }

            listed.status shouldBe HttpStatusCode.OK
            val command =
                Json
                    .parseToJsonElement(listed.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            command.getValue("commandId").jsonPrimitive.content shouldBe commandId
            command.getValue("status").jsonPrimitive.content shouldBe "PENDING"
            command.getValue("maxAccuracy").jsonPrimitive.content shouldBe "HIGH"
        }

    @Test
    fun `TC-5-API-01 a guest or a driver may not send commands`() =
        testApp {
            for (token in listOf(TestTokens.guest(), TestTokens.driver())) {
                val response = client.issue(highAccuracy, token)
                response.status shouldBe HttpStatusCode.Forbidden
                response.problem().code shouldBe "COMMAND_FORBIDDEN"
            }
        }

    @Test
    fun `TC-5-API-02 a negative interval is VALIDATION_FAILED and names the field`() =
        testApp {
            val response = client.issue("""{"kind":"SET_CONSTRAINT","maxAccuracy":"HIGH","minIntervalMs":-1,"reason":"x"}""", admin)

            response.status shouldBe HttpStatusCode.BadRequest
            response.problem().errors!!.map { it.field } shouldBe listOf("minIntervalMs")
        }

    @Test
    fun `geofences without regions, and an unknown kind, are VALIDATION_FAILED`() =
        testApp {
            client
                .issue("""{"kind":"REGISTER_GEOFENCES","regions":[]}""", admin)
                .problem()
                .errors!!
                .map { it.field } shouldBe
                listOf("regions")
            client
                .issue("""{"kind":"REBOOT"}""", admin)
                .problem()
                .errors!!
                .map { it.field } shouldBe listOf("kind")
        }

    @Test
    fun `a driver id that is not a UUID is VALIDATION_FAILED`() =
        testApp {
            client.get("/api/v1/admin/drivers/not-a-uuid/commands") { bearerAuth(admin) }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `without a token the API answers 401`() =
        testApp {
            client.issue(highAccuracy, token = null).status shouldBe HttpStatusCode.Unauthorized
        }
}
