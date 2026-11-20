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
import veeci.practicing.rts.devicecontrol.application.port.out.DeviceCommandRepository
import veeci.practicing.rts.devicecontrol.domain.CommandError
import veeci.practicing.rts.devicecontrol.domain.DeviceCommand
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.devicecontrol.InMemoryDeviceCommandRepository
import veeci.practicing.rts.testing.problem
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp
import org.koin.dsl.module as koinModule

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
    fun `every kind's reason reaches its COMMAND_ISSUED audit entry, and a command without one has none`() =
        testApp {
            val region = """{"id":"depot","lat":21.03,"lng":105.85,"radiusM":150}"""
            val bodies =
                listOf(
                    Triple("SET_CONSTRAINT", "support", highAccuracy),
                    Triple("REGISTER_GEOFENCES", "New depot", """{"kind":"REGISTER_GEOFENCES","regions":[$region],"reason":"New depot"}"""),
                    Triple("FLUSH_QUEUE", "Missing points", """{"kind":"FLUSH_QUEUE","reason":"Missing points"}"""),
                    Triple("FLUSH_QUEUE", null, """{"kind":"FLUSH_QUEUE"}"""),
                )
            val expected =
                bodies.map { (kind, reason, body) ->
                    val issued = client.issue(body, admin)
                    issued.status shouldBe HttpStatusCode.Accepted
                    AuditedCommand(
                        kind,
                        issued
                            .json()
                            .getValue("commandId")
                            .jsonPrimitive.content,
                        reason,
                    )
                }

            val audit = client.get("/api/v1/admin/audit?targetId=${driverId.value}&action=COMMAND_ISSUED") { bearerAuth(admin) }
            audit.shouldMatchContract()
            val recorded =
                audit
                    .json()
                    .getValue("items")
                    .jsonArray
                    .reversed() // newest first on the wire
                    .map { item ->
                        val details = item.jsonObject.getValue("details").jsonObject
                        AuditedCommand(
                            kind = details.getValue("kind").jsonPrimitive.content,
                            commandId = details.getValue("commandId").jsonPrimitive.content,
                            reason = item.jsonObject["reason"]?.jsonPrimitive?.content, // absent, not null, without one
                        )
                    }
            recorded shouldBe expected
        }

    @Test
    fun `a command the domain refuses is INVALID_COMMAND, a 422 the contract lists`() =
        testApp(
            env = TestDatabase.freshDatabaseEnv(), // the refusing repository must not see other tests' outbox events
            overrides = listOf(koinModule { single<DeviceCommandRepository> { RefusingRepository() } }),
        ) {
            val response = client.issue(highAccuracy, admin) // shouldMatchContract: the 422 must be in rts-v1.yaml

            response.status shouldBe HttpStatusCode.UnprocessableEntity
            response.problem().code shouldBe "INVALID_COMMAND"
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

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private data class AuditedCommand(
        val kind: String,
        val commandId: String,
        val reason: String?,
    )

    /**
     * Refuses every command the way the domain's own check does. Over HTTP the request check answers those cases with
     * 400 first, so this is the only way to reach INVALID_COMMAND through the real route and error handling.
     */
    private class RefusingRepository : DeviceCommandRepository by InMemoryDeviceCommandRepository() {
        override suspend fun insert(command: DeviceCommand): Unit =
            throw DomainException(CommandError.INVALID_COMMAND, "refused by the test")
    }
}
