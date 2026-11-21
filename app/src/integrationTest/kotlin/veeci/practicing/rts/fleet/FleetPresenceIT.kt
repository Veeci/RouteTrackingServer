package veeci.practicing.rts.fleet

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.fleet.FleetFixtures.driver
import veeci.practicing.rts.fleet.FleetFixtures.fleetRow
import veeci.practicing.rts.fleet.FleetFixtures.flushPresence
import veeci.practicing.rts.fleet.FleetFixtures.seed
import veeci.practicing.rts.fleet.FleetFixtures.updatedAt
import veeci.practicing.rts.fleet.application.port.out.FleetPresenceRepository
import veeci.practicing.rts.fleet.application.port.out.HeardLeft
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.events.OutboxRelay
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.testing.tracking.TrackingSamples
import veeci.practicing.rts.tracking.application.IngestBatch
import veeci.practicing.rts.tracking.application.OpenSession
import veeci.practicing.rts.tracking.application.TrackingService
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.SessionId
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Fleet lag fix C: "online" on the server's clock. Drivers send through the real tracking service, which tells the
 * fleet's presence; the tests flush it at once instead of waiting for its next run.
 */
@Testcontainers(disabledWithoutDocker = true)
class FleetPresenceIT {
    private val driver = UUID.randomUUID()

    @Test
    fun `a driver whose phone clock is 2 minutes slow shows online, with the position's real age`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            // Every fix the phone sends carries a time 2 minutes behind the server's.
            drive(phoneClockOffset = (-2).minutes)
            FleetFixtures.run { application.awaitOutboxDrained() }
            application.flushPresence()

            val detail = client.get("/api/v1/admin/drivers/$driver") { bearerAuth(TestTokens.operator()) }
            detail.shouldMatchContract()
            val json =
                Json
                    .parseToJsonElement(detail.bodyAsText())
                    .jsonObject
                    .getValue("driver")
                    .jsonObject
            json.getValue("online").jsonPrimitive.content shouldBe "true"
            val lastSeen = Instant.parse(json.getValue("lastSeenAt").jsonPrimitive.content)
            val heard = Instant.parse(json.getValue("heardAt").jsonPrimitive.content)
            (heard - lastSeen > 1.minutes) shouldBe true
            onlineList() shouldContain "\"driverId\":\"$driver\""
            overview() shouldContain "\"onlineDrivers\":1"
        }

    @Test
    fun `a driver shows online while the relay is behind and has not built their row yet`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            // No projection runs: the outbox keeps the driver's events, as during a backlog.
            application.get<OutboxRelay>().stop()

            drive()
            application.flushPresence()

            val row = application.fleetRow(driver).shouldNotBeNull()
            row.position.shouldBeNull()
            row.heardAt.shouldNotBeNull()
            row.enabled shouldBe true
            onlineList() shouldContain "\"driverId\":\"$driver\",\"enabled\":true,\"online\":true"
            overview() shouldContain "\"onlineDrivers\":1"
            application.unpublishedEvents() shouldBe 1L
        }

    @Test
    fun `heard_at only moves forward, and a write moves updated_at with it`() =
        testApp {
            val seeded = driver(ago = 30.seconds, id = driver)
            application.seed(seeded)
            val newer = seeded.heardAt!! + 10.seconds
            val writtenBefore = application.updatedAt(driver)

            application.saveHeard(mapOf(driver to newer)) shouldBe HeardLeft()
            val writtenAt = application.updatedAt(driver).shouldNotBeNull()
            application.saveHeard(mapOf(driver to newer - 20.seconds)) shouldBe HeardLeft()

            application.fleetRow(driver)?.heardAt shouldBe newer
            (writtenAt > writtenBefore!!) shouldBe true
            application.updatedAt(driver) shouldBe writtenAt
        }

    @Test
    fun `a row another transaction holds is skipped at once and returned as busy, and a driver with no row as missing`() =
        testApp {
            val seeded = driver(ago = 30.seconds, id = driver)
            application.seed(seeded)
            val stranger = UUID.randomUUID()
            val now = FleetFixtures.now()
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            coroutineScope {
                // The relay in the middle of a batch: it holds the row until its transaction ends.
                val relay =
                    async(Dispatchers.IO) {
                        application.get<TransactionRunner>().inTransaction {
                            TransactionManager.current().exec("SELECT 1 FROM fleet_drivers WHERE driver_id = '$driver' FOR UPDATE")
                            locked.complete(Unit)
                            release.await()
                        }
                    }
                locked.await()

                val left = withTimeout(5.seconds) { application.saveHeard(mapOf(driver to now, stranger to now)) }

                left.busy shouldContainExactly mapOf(driver to now)
                left.missing shouldContainExactly mapOf(stranger to now)
                release.complete(Unit)
                relay.await()
            }
            application.fleetRow(driver)?.heardAt shouldBe seeded.heardAt
            application.saveHeard(mapOf(driver to now)).busy.shouldBeEmpty()
            application.fleetRow(driver)?.heardAt shouldBe now
        }

    /** Hello and one batch through the tracking service; the fixes carry the phone's clock, [phoneClockOffset] off. */
    private suspend fun ApplicationTestBuilder.drive(phoneClockOffset: kotlin.time.Duration = kotlin.time.Duration.ZERO) {
        val tracking = application.get<TrackingService>()
        val session = SessionId(UUID.randomUUID())
        tracking.openSession(OpenSession(session, DeviceId("phone-$driver"), "it", UserId(driver)))
        tracking.ingest(IngestBatch(session, 1, listOf(TrackingSamples.reading(Clock.System.now() + phoneClockOffset))))
    }

    private suspend fun ApplicationTestBuilder.onlineList(): String =
        client.get("/api/v1/admin/drivers?online=true") { bearerAuth(TestTokens.operator()) }.bodyAsText()

    private suspend fun ApplicationTestBuilder.overview(): String =
        client.get("/api/v1/admin/overview") { bearerAuth(TestTokens.operator()) }.bodyAsText()

    private suspend fun Application.saveHeard(heard: Map<UUID, Instant>): HeardLeft =
        get<TransactionRunner>().inTransaction { get<FleetPresenceRepository>().saveHeard(heard, Clock.System.now()) }

    private suspend fun Application.unpublishedEvents(): Long =
        get<TransactionRunner>().inTransaction {
            TransactionManager.current().exec("SELECT count(*) FROM outbox WHERE published_at IS NULL") { rows ->
                rows.next()
                rows.getLong(1)
            } ?: 0
        }
}
