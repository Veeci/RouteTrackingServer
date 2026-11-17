package veeci.practicing.rts.platform.security

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.shared.UserId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

enum class TicketError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** Tickets open the admin fleet socket, so only staff get one. */
    TICKET_FORBIDDEN(ErrorCategory.FORBIDDEN),
}

/** A ticket as the client gets it: the only copy of the plain value. */
data class IssuedTicket(
    val ticket: String,
    val expiresAt: Instant,
)

/** The `ws_tickets` table (migration V21). */
internal object WsTicketsTable : Table("ws_tickets") {
    val hash = binary("hash")
    val userId = javaUUID("user_id")
    val role = text("role")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(hash)
}

/**
 * Single-use tickets for WebSockets opened by a browser (phase 11c), which cannot set an Authorization header. A
 * ticket is 32 random bytes, valid for [TTL], bound to the user and role that asked for it. Only its SHA-256 is
 * stored. Redeeming deletes the row in the same statement that reads it, so two instances racing for one ticket
 * cannot both win: Postgres lets one DELETE take the row and the other finds nothing.
 */
class WsTickets(
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    suspend fun issue(actor: Actor): IssuedTicket {
        if (!actor.role.isStaff) throw DomainException(TicketError.TICKET_FORBIDDEN, "Tickets are for operators and admins")
        val bytes = ByteArray(TICKET_BYTES).also(random::nextBytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val expiresAt = now() + TTL
        transactions.inTransaction {
            WsTicketsTable.insert {
                it[hash] = sha256(ticket)
                it[userId] = actor.userId.value
                it[role] = actor.role.name
                it[WsTicketsTable.expiresAt] = expiresAt
            }
        }
        return IssuedTicket(ticket, expiresAt)
    }

    /** The caller [ticket] was issued to, or null when it is unknown, used or expired. Works once. */
    suspend fun redeem(ticket: String): Actor? {
        if (ticket.length > MAX_TICKET_LENGTH) return null
        val now = now()
        val row =
            transactions.inTransaction {
                WsTicketsTable
                    .deleteReturning(listOf(WsTicketsTable.userId, WsTicketsTable.role)) {
                        (WsTicketsTable.hash eq sha256(ticket)) and (WsTicketsTable.expiresAt greater now)
                    }.singleOrNull()
            }
        return row?.let { found ->
            Role.entries.firstOrNull { it.name == found[WsTicketsTable.role] }?.let { Actor(UserId(found[WsTicketsTable.userId]), it) }
        }
    }

    /** Deletes the tickets that expired unredeemed; the retention job calls it. Returns how many. */
    suspend fun purgeExpired(): Int {
        val now = now()
        return transactions.inTransaction { WsTicketsTable.deleteWhere { expiresAt lessEq now } }
    }

    private fun now(): Instant = clock.instant().toKotlinInstant()

    companion object {
        val TTL = 30.seconds
        private const val TICKET_BYTES = 32

        /** 32 bytes in base64url are 43 characters; anything much longer is not a ticket and is not hashed. */
        private const val MAX_TICKET_LENGTH = 64

        private fun sha256(ticket: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(ticket.toByteArray())
    }
}

@Serializable
data class WsTicketResponse(
    /** Put it in the socket URL as `?ticket=`. It works once. */
    val ticket: String,
    val expiresAt: Instant,
)

/** `POST /api/v1/admin/ws-tickets` (phase 11c, OPERATOR and ADMIN). Mount inside `authenticate(AUTH_JWT)`. */
fun Route.wsTicketRoutes(tickets: WsTickets) {
    post("/api/v1/admin/ws-tickets") {
        val issued = tickets.issue(call.actor())
        call.respond(WsTicketResponse(issued.ticket, issued.expiresAt))
    }
}
