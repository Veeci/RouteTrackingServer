package veeci.practicing.rts.fleet

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.fleet.FleetFixtures.awaitOutboxDrained
import veeci.practicing.rts.fleet.FleetFixtures.driver
import veeci.practicing.rts.fleet.FleetFixtures.fleetRow
import veeci.practicing.rts.fleet.FleetFixtures.seed
import veeci.practicing.rts.fleet.adapter.inbound.web.DriverDetailResponse
import veeci.practicing.rts.fleet.adapter.inbound.web.FleetOverviewResponse
import veeci.practicing.rts.fleet.adapter.inbound.web.FleetPageResponse
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.platform.events.OutboxRelay
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.tracking.application.OpenSession
import veeci.practicing.rts.tracking.application.TrackingService
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.DeviceInfo
import veeci.practicing.rts.tracking.domain.SessionId
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The fleet's admin REST API against Postgres (phase 11b). Every test has a database of its own. */
@Testcontainers(disabledWithoutDocker = true)
class FleetApiIT {
    private val json = Json { ignoreUnknownKeys = true }
    private val operator = TestTokens.operator()
    private val admin = TestTokens.admin()

    @Test
    fun `TC-11-GEO-01 a map area holds the drivers inside it and on its edges, not those outside or without a position`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            // bbox = 105.80,21.00,105.90,21.10
            val inside = driver(lat = 21.05, lng = 105.85)
            val edges =
                listOf(
                    driver(lat = 21.00, lng = 105.85),
                    driver(lat = 21.10, lng = 105.85),
                    driver(lat = 21.05, lng = 105.80),
                    driver(lat = 21.05, lng = 105.90),
                    driver(lat = 21.10, lng = 105.90), // a corner
                )
            val outside =
                listOf(driver(lat = 20.9999, lng = 105.85), driver(lat = 21.05, lng = 105.9001), driver(lat = -21.05, lng = -105.85))
            val nowhere = driver(ago = null)
            application.seed(listOf(inside, nowhere) + edges + outside)

            val page = list("bbox=105.80,21.00,105.90,21.10&limit=100")

            page.items.map { it.driverId } shouldContainExactlyInAnyOrder (listOf(inside) + edges).map { it.driverId.toString() }
        }

    @Test
    fun `TC-11-API-01 filters combine with keyset paging, newest seen first, and no driver is listed twice`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val same = UUID.fromString("00000000-0000-4000-8000-000000000001")
            val sameLater = UUID.fromString("00000000-0000-4000-8000-000000000002")
            val tie = driver(name = "Tie one", ago = 20.seconds, id = same)
            val drivers =
                listOf(
                    driver(name = "Alice Nguyen", ago = 1.seconds),
                    tie,
                    tie.copy(driverId = sameLater, displayName = "Tie two"), // the same last-seen time: ordered by id
                    driver(name = "Bob 50%_off", ago = 30.seconds, onTrip = true),
                    driver(name = "Offline Carol", ago = 10.minutes),
                    driver(name = "On trip, offline", ago = 20.minutes, onTrip = true),
                    driver(name = "Never seen A", ago = null),
                    driver(name = "Never seen B", ago = null),
                )
            application.seed(drivers)
            // Newest seen first, never seen last, ties by id: the list order. Ids compare as text, which is Postgres's
            // unsigned byte order; java.util.UUID.compareTo compares signed longs and puts 8... to f... first.
            val ordered =
                drivers.sortedWith(
                    compareBy<FleetDriver, Instant?>(nullsLast(reverseOrder())) { it.lastSeenAt }.thenBy { it.driverId.toString() },
                )

            fun ids(vararg names: String) = ordered.filter { it.displayName in names }.map { it.driverId.toString() }

            walk("limit=2") shouldContainExactly ordered.map { it.driverId.toString() }
            walk("limit=1&online=true&onTrip=false") shouldContainExactly ids("Alice Nguyen", "Tie one", "Tie two")
            walk("limit=1&onTrip=true") shouldContainExactly ids("Bob 50%_off", "On trip, offline")
            walk("limit=3&online=false") shouldContainExactly ids("Offline Carol", "On trip, offline", "Never seen A", "Never seen B")
            walk("q=50%25") shouldContainExactly ids("Bob 50%_off") // % is taken literally
            walk("q=TIE") shouldContainExactly listOf(same.toString(), sameLater.toString())
            walk("q=$sameLater") shouldContainExactly listOf(sameLater.toString())
            walk("q=nobody").shouldBeEmpty()
        }

    @Test
    fun `TC-11-API-04 the overview counts the fleet, and a second call within 10 s is served from the cache`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            application.seed(driver(onTrip = true), driver(), driver(ago = 5.minutes, enabled = false))
            // A real trip accepted by a fourth driver: one active trip, and the projection puts that driver on it.
            val trips = TripApi(client)
            val driverId = UserId(UUID.randomUUID())
            trips.moved(trips.created(TestTokens.guest()).id, TestTokens.driver(driverId), "accept")
            application.awaitOutboxDrained()
            application.fleetRow(driverId.value)?.onTrip shouldBe true

            val first = overview()
            first.onlineDrivers shouldBe 2
            first.driversOnTrip shouldBe 2
            first.activeTrips shouldBe 1
            first.disabledDrivers shouldBe 1

            application.seed(driver(), driver())
            overview() shouldBe first // same numbers, same computedAt
        }

    @Test
    fun `TC-11-API-05 the CSV streams every driver, page after page, with a header row`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val many = (1..1_001).map { driver(name = "Driver $it", ago = it.seconds) } // more than two export pages
            val seen = Clock.System.now() - 2.minutes
            val tricky =
                driver(
                    name = "=cmd|' /C calc'!A0, \"Bob\"",
                    ago = null,
                ).copy(position = DriverPosition(21.5, 105.25, 12.3, null, seen))
            application.seed(many + tricky)

            val response = client.get("/api/v1/admin/drivers.csv") { bearerAuth(admin) }

            response.status shouldBe HttpStatusCode.OK
            response.shouldMatchContract()
            response.headers[HttpHeaders.ContentType].shouldNotBeNull() shouldStartWith "text/csv"
            response.headers[HttpHeaders.ContentDisposition] shouldBe "attachment; filename=drivers.csv"
            val lines = response.bodyAsText().split("\r\n").dropLast(1)
            lines.first() shouldStartWith "driverId,displayName,enabled,online,lastSeenAt,heardAt,lat,lng,speedMps"
            lines.drop(1) shouldHaveSize 1_002
            lines.drop(1).map { it.substringBefore(",") }.toSet() shouldHaveSize 1_002
            val trickyLine = lines.single { it.startsWith(tricky.driverId.toString()) }
            trickyLine shouldStartWith "${tricky.driverId},\"'=cmd|' /C calc'!A0, \"\"Bob\"\"\",true,false,"
            trickyLine.split(",").let { it[it.size - 7] } shouldBe "12.3" // speedMps, counted from the end: the name holds commas
        }

    @Test
    fun `the driver detail adds the account, recent sessions and trips, and an unknown driver is 404`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val id = UUID.randomUUID()
            val token = devDriver(id, "Minh")
            application.get<TrackingService>().openSession(
                OpenSession(
                    SessionId(UUID.randomUUID()),
                    DeviceId("phone-1"),
                    "it",
                    UserId(id),
                    DeviceInfo("Google", "Pixel 8", 35, null, true),
                ),
            )
            val trips = TripApi(client)
            trips.moved(trips.created(TestTokens.guest()).id, token, "accept")
            application.awaitOutboxDrained()

            val response = client.get("/api/v1/admin/drivers/$id") { bearerAuth(operator) }
            response.status shouldBe HttpStatusCode.OK
            response.shouldMatchContract()
            val detail = json.decodeFromString(DriverDetailResponse.serializer(), response.bodyAsText())
            detail.driver.displayName shouldBe "Minh"
            detail.driver.trip?.status shouldBe "ACCEPTED"
            detail.recentSessions.single().model shouldBe "Pixel 8"
            detail.recentTrips.single().status shouldBe "ACCEPTED"
            detail.health.shouldBeNull()

            client.get("/api/v1/admin/drivers/${UUID.randomUUID()}") { bearerAuth(operator) }.apply {
                status shouldBe HttpStatusCode.NotFound
                shouldMatchContract()
            }
            client.get("/api/v1/admin/drivers/not-a-uuid") { bearerAuth(operator) }.status shouldBe HttpStatusCode.BadRequest
            client.get("/api/v1/admin/drivers?bbox=1,2,3") { bearerAuth(operator) }.apply {
                status shouldBe HttpStatusCode.BadRequest
                shouldMatchContract()
            }
        }

    @Test
    fun `right after a disable the detail says disabled from the account, while the list waits for the outbox`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val id = UUID.randomUUID()
            devDriver(id, "Lan")
            application.awaitOutboxDrained()
            application.fleetRow(id)!!.enabled shouldBe true
            application.get<OutboxRelay>().stop() // from here the read model hears of no account change

            client
                .post("/api/v1/admin/drivers/$id/disable") {
                    bearerAuth(admin)
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"Unsafe driving"}""")
                }.status shouldBe HttpStatusCode.OK
            application.fleetRow(id)!!.enabled shouldBe true // the row still lags, so the detail below must not use it

            val response = client.get("/api/v1/admin/drivers/$id") { bearerAuth(operator) }
            response.shouldMatchContract()
            val detail = json.decodeFromString(DriverDetailResponse.serializer(), response.bodyAsText())
            detail.driver.enabled shouldBe false
            detail.disabledAt.shouldNotBeNull()
            detail.disabledReason shouldBe "Unsafe driving"
            list("").items.single().enabled shouldBe true // the list reads only the read model; its lag is expected
        }

    @Test
    fun `a rebuild is audited with the admin and the number of drivers`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            devDriver(UUID.randomUUID(), "Hoa")
            application.awaitOutboxDrained()

            client.post("/api/v1/admin/fleet/rebuild") { bearerAuth(admin) }.apply {
                status shouldBe HttpStatusCode.OK
                shouldMatchContract()
                bodyAsText() shouldBe """{"drivers":1}"""
            }
            val audit = client.get("/api/v1/admin/audit?action=FLEET_REBUILT") { bearerAuth(admin) }.bodyAsText()
            audit.contains("\"targetType\":\"FLEET\"") shouldBe true
            audit.contains("\"drivers\":\"1\"") shouldBe true
        }

    private suspend fun ApplicationTestBuilder.list(query: String): FleetPageResponse {
        val response = client.get("/api/v1/admin/drivers?$query") { bearerAuth(operator) }
        response.status shouldBe HttpStatusCode.OK
        response.shouldMatchContract()
        return json.decodeFromString(FleetPageResponse.serializer(), response.bodyAsText())
    }

    /** Every page of [query], following nextCursor; the driver ids in the order they came. */
    private suspend fun ApplicationTestBuilder.walk(query: String): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = list(query + (cursor?.let { "&cursor=$it" } ?: ""))
            ids += page.items.map { it.driverId }
            cursor = page.nextCursor
        } while (cursor != null)
        return ids
    }

    private suspend fun ApplicationTestBuilder.overview(): FleetOverviewResponse {
        val response = client.get("/api/v1/admin/overview") { bearerAuth(operator) }
        response.shouldMatchContract()
        return json.decodeFromString(FleetOverviewResponse.serializer(), response.bodyAsText())
    }

    /** A DRIVER account through the dev token endpoint (it writes the identity event), and its token. */
    private suspend fun ApplicationTestBuilder.devDriver(
        id: UUID,
        name: String,
    ): String {
        val response =
            client.post("/api/v1/dev/tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"role":"DRIVER","displayName":"$name","userId":"$id"}""")
            }
        return Regex(""""token":"([^"]+)"""").find(response.bodyAsText())!!.groupValues[1]
    }
}
