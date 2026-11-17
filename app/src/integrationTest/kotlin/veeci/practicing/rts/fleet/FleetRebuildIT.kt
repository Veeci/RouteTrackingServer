package veeci.practicing.rts.fleet

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.fleet.FleetFixtures.awaitOutboxDrained
import veeci.practicing.rts.fleet.FleetFixtures.fleetTable
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.testing.tracking.TrackingSamples
import veeci.practicing.rts.testing.trip.TripApi
import veeci.practicing.rts.tracking.application.IngestBatch
import veeci.practicing.rts.tracking.application.OpenSession
import veeci.practicing.rts.tracking.application.ReportStatus
import veeci.practicing.rts.tracking.application.TrackingService
import veeci.practicing.rts.tracking.domain.DeviceId
import veeci.practicing.rts.tracking.domain.SessionId
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReported
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * TC-11-PRJ-05: the best single check of the event handlers. Drivers act through the real services (accounts,
 * sessions, fixes, device status, trips, a disable); the relay builds the read model from the events; then a rebuild
 * from the source data must give the same table, column by column.
 */
@Testcontainers(disabledWithoutDocker = true)
class FleetRebuildIT {
    @Test
    fun `TC-11-PRJ-05 a rebuild from the source data gives the same rows as the events`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val trips = TripApi(client)
            val admin = TestTokens.admin()

            // Anh: an account, two batches, a status, a completed trip, then disabled.
            val anh = UUID.randomUUID()
            val anhToken = devDriver(anh, "Anh")
            drive(anh, statusAt = 3)
            trips.moved(trips.created(TestTokens.guest()).id, anhToken, "accept", "arrive", "start", "complete")
            client
                .post("/api/v1/admin/drivers/$anh/disable") {
                    bearerAuth(admin)
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"Test"}""")
                }.status shouldBe HttpStatusCode.OK

            // Binh: no account row (a token only), fixes, and a trip still going.
            val binh = UUID.randomUUID()
            drive(binh, statusAt = null)
            trips.moved(trips.created(TestTokens.guest()).id, TestTokens.driver(UserId(binh)), "accept", "arrive")

            // Chi: an account and nothing else. Dung: a trip the guest cancelled after Dung accepted it.
            devDriver(UUID.randomUUID(), "Chi")
            val dung = UUID.randomUUID()
            val guest = TestTokens.guest()
            val cancelled = trips.created(guest).id
            trips.moved(cancelled, TestTokens.driver(UserId(dung)), "accept")
            trips.act(cancelled, "cancel", guest).status shouldBe HttpStatusCode.OK

            application.awaitOutboxDrained()
            val fromEvents = application.fleetTable()
            fromEvents shouldHaveSize 4

            client.post("/api/v1/admin/fleet/rebuild") { bearerAuth(admin) }.bodyAsText() shouldBe """{"drivers":4}"""

            application.fleetTable() shouldBe fromEvents
        }

    /** A session with two batches of fixes, 12.3 m/s (not exact in a 4-byte float), and a device status. */
    private suspend fun ApplicationTestBuilder.drive(
        driver: UUID,
        statusAt: Int?,
    ) {
        val tracking = application.get<TrackingService>()
        val session = SessionId(UUID.randomUUID())
        val now = Clock.System.now()
        tracking.openSession(OpenSession(session, DeviceId("phone-$driver"), "it", UserId(driver)))
        listOf(1L to 10, 2L to 5).forEach { (seq, ago) ->
            val reading = TrackingSamples.reading(now - ago.seconds)
            tracking.ingest(
                IngestBatch(session, seq, listOf(reading.copy(lat = reading.lat + seq * 0.0001, speedMps = 12.3, bearingDeg = 271.7))),
            )
        }
        statusAt?.let {
            tracking.reportStatus(
                ReportStatus(session, now - it.seconds, DeviceStatusReported.Battery(64, false, null, null), "IN_VEHICLE"),
            )
        }
    }

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
