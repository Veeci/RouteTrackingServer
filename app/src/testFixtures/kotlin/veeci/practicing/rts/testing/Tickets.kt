package veeci.practicing.rts.testing

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.ktor.ext.get
import java.util.UUID
import javax.sql.DataSource

/** `POST` here with a staff token to get a ticket for an admin socket (phases 11c and 12a). */
const val WS_TICKETS = "/api/v1/admin/ws-tickets"

/** A ticket for [token] (an OPERATOR by default), asked for with [http]. The response must match the contract. */
suspend fun SocketTestApp.ticket(
    token: String = TestTokens.operator(),
    http: HttpClient = client,
): String {
    val response = http.post(WS_TICKETS) { bearerAuth(token) }
    response.status shouldBe HttpStatusCode.OK
    response.shouldMatchContract()
    return Json
        .parseToJsonElement(response.bodyAsText())
        .jsonObject
        .getValue("ticket")
        .jsonPrimitive.content
}

/**
 * A ticket the API never issues (for a GUEST or DRIVER), stored as the server stores one: by its SHA-256. The tests of
 * close 4403 need it, because `POST /api/v1/admin/ws-tickets` refuses those roles.
 */
fun Application.plantTicket(role: String): String {
    val ticket = "planted-${UUID.randomUUID()}"
    get<DataSource>().connection.use { connection ->
        connection.prepareStatement(PLANT_TICKET).use { statement ->
            statement.setString(1, ticket)
            statement.setObject(2, UUID.randomUUID())
            statement.setString(3, role)
            statement.executeUpdate()
        }
    }
    return ticket
}

private const val PLANT_TICKET =
    "INSERT INTO ws_tickets (hash, user_id, role, expires_at) VALUES (sha256(convert_to(?, 'UTF8')), ?, ?, now() + interval '30 seconds')"
