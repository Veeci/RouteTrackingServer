package veeci.practicing.rts.fleet

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.getKoin
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.fleet.FleetFixtures.fleetRow
import veeci.practicing.rts.fleet.FleetFixtures.updatedAt
import veeci.practicing.rts.identity.domain.event.DriverAccountChanged
import veeci.practicing.rts.identity.domain.event.DriverAccountChangedV1
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReported
import veeci.practicing.rts.tracking.domain.event.DeviceStatusReportedV1
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.tracking.domain.event.FixesAcceptedV1
import veeci.practicing.rts.trip.domain.event.TripStatus
import veeci.practicing.rts.trip.domain.event.TripStatusChanged
import veeci.practicing.rts.trip.domain.event.TripStatusChangedV1
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The fleet's event handlers against Postgres (phase 11b). Each test hands events to the handlers the relay would
 * call, through the real Koin graph, in a database of its own.
 */
@Testcontainers(disabledWithoutDocker = true)
class FleetProjectionIT {
    private val driver = UUID.randomUUID()
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()) // Postgres keeps microseconds

    @Test
    fun `TC-11-PRJ-01 accepted fixes give the row the batch's last position, and the driver is online`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            application.deliver(
                FixesAcceptedV1.TYPE,
                FixesAcceptedV1.serializer(),
                FixesAcceptedV1.of(
                    fixes(
                        now - 3.seconds,
                        now - 1.seconds,
                    ),
                ),
            )

            val row = application.fleetRow(driver).shouldNotBeNull()
            row.position?.lat shouldBe LAST.lat
            row.position?.lng shouldBe LAST.lng
            row.position?.speedMps shouldBe 12.3 // stored as a 4-byte float, read back as the same decimal
            row.lastSeenAt shouldBe row.position?.at

            client.get("/api/v1/admin/drivers?online=true") { bearerAuth(TestTokens.operator()) }.apply {
                shouldMatchContract()
                bodyAsText() shouldContain "\"driverId\":\"$driver\",\"enabled\":true,\"online\":true"
            }
        }

    @Test
    fun `TC-11-PRJ-02 a repeated event and a late older event change nothing, not even the write time`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val newer = FixesAcceptedV1.of(fixes(now - 1.seconds))
            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), newer)
            val before = application.fleetRow(driver)
            val writtenAt = application.updatedAt(driver).shouldNotBeNull()

            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), newer)
            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), FixesAcceptedV1.of(fixes(now - 30.seconds)))
            val status =
                DeviceStatusReported(
                    UUID.randomUUID(),
                    "phone",
                    driver,
                    now - 2.seconds,
                    DeviceStatusReported.Battery(70, true, null, null),
                    "STILL",
                )
            application.deliver(DeviceStatusReportedV1.TYPE, DeviceStatusReportedV1.serializer(), DeviceStatusReportedV1.of(status))
            val withStatus = application.updatedAt(driver)
            application.deliver(DeviceStatusReportedV1.TYPE, DeviceStatusReportedV1.serializer(), DeviceStatusReportedV1.of(status))

            application.fleetRow(driver) shouldBe before?.copy(device = application.fleetRow(driver)?.device)
            application.fleetRow(driver)?.device?.batteryPct shouldBe 70
            application.fleetRow(driver)?.lastSeenAt shouldBe now - 1.seconds // the status is older than the fix
            (withStatus!! >= writtenAt) shouldBe true
            application.updatedAt(driver) shouldBe withStatus // the repeated status wrote nothing
        }

    @Test
    fun `TC-11-PRJ-04 a trip sets the current trip, and its completion clears it`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val trip = UUID.randomUUID()
            application.deliver(
                TripStatusChangedV1.TYPE,
                TripStatusChangedV1.serializer(),
                stage(
                    trip,
                    TripStatus.ACCEPTED,
                    now - 60.seconds,
                ),
            )
            application.fleetRow(driver)?.trip?.currentTripId shouldBe trip

            application.deliver(TripStatusChangedV1.TYPE, TripStatusChangedV1.serializer(), stage(trip, TripStatus.COMPLETED, now))
            val row = application.fleetRow(driver).shouldNotBeNull()
            row.trip?.currentTripId.shouldBeNull()
            row.trip?.status shouldBe "COMPLETED"
            row.onTrip shouldBe false
        }

    @Test
    fun `events without a driver are skipped, and an account event names the driver`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), FixesAcceptedV1.of(fixes(now).copy(driverId = null)))
            val requested = TripStatusChanged(UUID.randomUUID(), UUID.randomUUID(), null, null, TripStatus.REQUESTED, now)
            application.deliver(TripStatusChangedV1.TYPE, TripStatusChangedV1.serializer(), TripStatusChangedV1.of(requested))
            application.fleetRow(driver).shouldBeNull()

            val account = DriverAccountChanged(driver, "Lan", enabled = false, at = now)
            application.deliver(DriverAccountChangedV1.TYPE, DriverAccountChangedV1.serializer(), DriverAccountChangedV1.of(account))
            application.fleetRow(driver)?.displayName shouldBe "Lan"
            application.fleetRow(driver)?.enabled shouldBe false
        }

    private fun fixes(vararg times: Instant) =
        FixesAccepted(
            UUID.randomUUID(),
            "phone-1",
            driver,
            1,
            times.mapIndexed { i, at ->
                val point = if (i == times.lastIndex) LAST else GeoPoint(21.0, 105.8)
                FixesAccepted.Position(point, at, 12.3, 45.0, i * 10.0)
            },
        )

    private fun stage(
        trip: UUID,
        to: TripStatus,
        at: Instant,
    ) = TripStatusChangedV1.of(TripStatusChanged(trip, UUID.randomUUID(), driver, null, to, at))

    /** Hands the event to every handler of its type, as the relay would. */
    private suspend fun <T> Application.deliver(
        type: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        event: T,
    ) {
        val payload = Json.encodeToJsonElement(serializer, event)
        getKoin()
            .getAll<OutboxEventHandler>()
            .filter { it.eventType == type && it::class.java.packageName.startsWith("veeci.practicing.rts.fleet") }
            .forEach { it.handle(payload) }
    }

    private companion object {
        val LAST = GeoPoint(21.0285, 105.8542)
    }
}
