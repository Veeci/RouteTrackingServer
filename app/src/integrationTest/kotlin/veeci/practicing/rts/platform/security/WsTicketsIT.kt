package veeci.practicing.rts.platform.security

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.koin.ktor.ext.getKoin
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.jobs.ScheduledJob
import veeci.practicing.rts.shared.Actor
import veeci.practicing.rts.shared.Role
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.testApp

/** The ticket store (phase 11c): bound to its user, single use, hashed at rest, purged by the retention job. */
@Testcontainers(disabledWithoutDocker = true)
class WsTicketsIT {
    @Test
    fun `TC-11-SEC-03 a ticket redeems once, as the user and role it was issued to`() =
        testApp {
            val tickets = application.get<WsTickets>()
            val alice = Actor(TestTokens.newUser(), Role.OPERATOR)
            val bob = Actor(TestTokens.newUser(), Role.ADMIN)
            val forAlice = tickets.issue(alice).ticket
            val forBob = tickets.issue(bob).ticket

            tickets.redeem(forAlice) shouldBe alice
            tickets.redeem(forAlice).shouldBeNull()
            tickets.redeem(forBob) shouldBe bob
        }

    @Test
    fun `the table holds the SHA-256 of a ticket, never the ticket`() =
        testApp {
            val ticket = application.get<WsTickets>().issue(Actor(TestTokens.newUser(), Role.ADMIN)).ticket

            application.count("SELECT count(*) FROM ws_tickets WHERE hash = sha256(convert_to('$ticket', 'UTF8'))") shouldBe 1
            application.count("SELECT count(*) FROM ws_tickets WHERE hash = convert_to('$ticket', 'UTF8')") shouldBe 0
        }

    @Test
    fun `the retention job deletes expired tickets and keeps valid ones`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val tickets = application.get<WsTickets>()
            val valid = tickets.issue(Actor(TestTokens.newUser(), Role.OPERATOR)).ticket
            val expired = tickets.issue(Actor(TestTokens.newUser(), Role.OPERATOR)).ticket
            val expire =
                "UPDATE ws_tickets SET expires_at = now() - interval '1 second' " +
                    "WHERE hash = sha256(convert_to('$expired', 'UTF8'))"
            application.count("$expire RETURNING 1") shouldBe 1

            application
                .getKoin()
                .getAll<ScheduledJob>()
                .single { it.name == "location-retention" }
                .run()

            application.count("SELECT count(*) FROM ws_tickets") shouldBe 1
            tickets.redeem(valid) shouldNotBe null
        }

    private suspend fun io.ktor.server.application.Application.count(query: String): Int =
        get<TransactionRunner>().inTransaction {
            TransactionManager.current().exec(
                query,
                explicitStatementType = StatementType.SELECT,
            ) { rows ->
                rows.next()
                rows.getInt(1)
            }
        } ?: 0
}
