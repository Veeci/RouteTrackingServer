package veeci.practicing.rts.fleet

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.koin.ktor.ext.getKoin
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.fleet.FleetFixtures.fleetRow
import veeci.practicing.rts.fleet.FleetFixtures.flushPresence
import veeci.practicing.rts.fleet.FleetFixtures.updatedAt
import veeci.practicing.rts.identity.domain.event.DriverAccountChanged
import veeci.practicing.rts.identity.domain.event.DriverAccountChangedV1
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.platform.events.OutboxEventHandler
import veeci.practicing.rts.shared.UserId
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.testApp
import veeci.practicing.rts.tracking.application.api.DriverHeard
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
    fun `TC-11-PRJ-01 accepted fixes give the row the batch's last position, and ingest, not the event, makes the driver online`() =
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
            // Fleet lag fix C: the relay's event carries the phone's times only, so it says nothing about presence.
            row.heardAt.shouldBeNull()
            client.get("/api/v1/admin/drivers?online=true") { bearerAuth(TestTokens.operator()) }.bodyAsText() shouldNotContain "$driver"

            application.get<DriverHeard>().heard(UserId(driver), now)
            application.flushPresence()

            client.get("/api/v1/admin/drivers?online=true") { bearerAuth(TestTokens.operator()) }.apply {
                shouldMatchContract()
                bodyAsText() shouldContain "\"driverId\":\"$driver\",\"enabled\":true,\"online\":true"
            }
        }

    @Test
    fun `the relay's positions, statuses, trips and accounts leave heard_at as the presence flush wrote it`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            application.get<DriverHeard>().heard(UserId(driver), now)
            application.flushPresence()

            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), FixesAcceptedV1.of(fixes(now - 1.seconds)))
            application.deliver(
                DeviceStatusReportedV1.TYPE,
                DeviceStatusReportedV1.serializer(),
                DeviceStatusReportedV1.of(status(now, 70)),
            )
            application.deliver(
                TripStatusChangedV1.TYPE,
                TripStatusChangedV1.serializer(),
                stage(UUID.randomUUID(), TripStatus.ACCEPTED, now),
            )
            val account = DriverAccountChanged(driver, "Lan", enabled = true, at = now)
            application.deliver(DriverAccountChangedV1.TYPE, DriverAccountChangedV1.serializer(), DriverAccountChangedV1.of(account))

            application.fleetRow(driver).shouldNotBeNull().apply {
                heardAt shouldBe now
                displayName shouldBe "Lan"
                onTrip shouldBe true
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

    @Test
    fun `an older fix after a newer one leaves the newer position, and writes nothing`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), FixesAcceptedV1.of(fixes(now - 1.seconds)))
            val writtenAt = application.updatedAt(driver)

            val older = fixes(now - 30.seconds).let { it.copy(positions = it.positions.map { p -> p.copy(point = GeoPoint(20.0, 105.0)) }) }
            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), FixesAcceptedV1.of(older))

            val row = application.fleetRow(driver).shouldNotBeNull()
            row.position?.lat shouldBe LAST.lat
            row.position?.at shouldBe now - 1.seconds
            row.lastSeenAt shouldBe now - 1.seconds
            application.updatedAt(driver) shouldBe writtenAt
        }

    @Test
    fun `a first position creates the row of a driver who has none, with the defaults`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            application.fleetRow(driver).shouldBeNull()

            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), FixesAcceptedV1.of(fixes(now - 2.seconds)))

            val row = application.fleetRow(driver).shouldNotBeNull()
            row.displayName.shouldBeNull()
            row.enabled shouldBe true
            row.device.shouldBeNull()
            row.trip.shouldBeNull()
            row.position?.bearingDeg shouldBe 45.0
            row.lastSeenAt shouldBe now - 2.seconds
            application.updatedAt(driver).shouldNotBeNull()
        }

    @Test
    fun `a position and a status keep the rest of the row, and last seen is the newer of the two`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val tripId = UUID.randomUUID()
            val account = DriverAccountChanged(driver, "Lan", enabled = false, at = now)
            application.deliver(DriverAccountChangedV1.TYPE, DriverAccountChangedV1.serializer(), DriverAccountChangedV1.of(account))
            application.deliver(TripStatusChangedV1.TYPE, TripStatusChangedV1.serializer(), stage(tripId, TripStatus.ACCEPTED, now))
            application.deliver(
                DeviceStatusReportedV1.TYPE,
                DeviceStatusReportedV1.serializer(),
                DeviceStatusReportedV1.of(
                    status(now - 5.seconds, 70),
                ),
            )

            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), FixesAcceptedV1.of(fixes(now - 10.seconds)))
            application.fleetRow(driver).shouldNotBeNull().apply {
                displayName shouldBe "Lan"
                enabled shouldBe false
                trip?.currentTripId shouldBe tripId
                device?.batteryPct shouldBe 70
                position?.at shouldBe now - 10.seconds
                lastSeenAt shouldBe now - 5.seconds // the status is newer than the fix
            }

            application.deliver(FixesAcceptedV1.TYPE, FixesAcceptedV1.serializer(), FixesAcceptedV1.of(fixes(now - 1.seconds)))
            application.fleetRow(driver)?.lastSeenAt shouldBe now - 1.seconds

            application.deliver(
                DeviceStatusReportedV1.TYPE,
                DeviceStatusReportedV1.serializer(),
                DeviceStatusReportedV1.of(
                    status(now - 3.seconds, 60),
                ),
            )
            application.fleetRow(driver).shouldNotBeNull().apply {
                device?.batteryPct shouldBe 60
                lastSeenAt shouldBe now - 1.seconds // the fix is newer than the status
            }
            application.deliver(
                DeviceStatusReportedV1.TYPE,
                DeviceStatusReportedV1.serializer(),
                DeviceStatusReportedV1.of(
                    status(now - 4.seconds, 50),
                ),
            )
            application.fleetRow(driver)?.device?.batteryPct shouldBe 60 // an older status changes nothing
        }

    @Test
    fun `fix A1 one position event costs the relay two statements, the fleet upsert and one trip read`() =
        testApp(env = TestDatabase.freshDatabaseEnv()) {
            val payload = Json.encodeToJsonElement(FixesAcceptedV1.serializer(), FixesAcceptedV1.of(fixes(now - 1.seconds)))
            val statements = mutableListOf<String>()

            application.get<TransactionRunner>().inTransaction {
                TransactionManager.current().addLogger(
                    object : SqlLogger {
                        override fun log(
                            context: StatementContext,
                            transaction: Transaction,
                        ) {
                            statements += context.sql(transaction)
                        }
                    },
                )
                application.getKoin().getAll<OutboxEventHandler>().filter { it.eventType == FixesAcceptedV1.TYPE }.forEach {
                    it.handle(
                        payload,
                    )
                }
            }

            statements.size shouldBe 2
            statements.count { it.startsWith("INSERT INTO fleet_drivers") } shouldBe 1
            statements.count { it.startsWith("SELECT") && "FROM trips" in it } shouldBe 1
            application.fleetRow(driver)?.position?.at shouldBe now - 1.seconds
        }

    private fun status(
        at: Instant,
        batteryPct: Int,
    ) = DeviceStatusReported(UUID.randomUUID(), "phone", driver, at, DeviceStatusReported.Battery(batteryPct, false, null, null), "STILL")

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
