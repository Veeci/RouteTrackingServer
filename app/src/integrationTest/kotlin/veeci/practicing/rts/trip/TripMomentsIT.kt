package veeci.practicing.rts.trip

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.platform.events.OutboxWriter
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.testing.trip.TripSamples.PICKUP
import veeci.practicing.rts.testing.trip.TripSamples.T0
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingSessionRepository
import veeci.practicing.rts.tracking.application.TrackingPresence
import veeci.practicing.rts.tracking.domain.event.FixesAccepted
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedRuleStateStore
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripEventOutbox
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripMomentRepository
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRepository
import veeci.practicing.rts.trip.application.TripEventService
import veeci.practicing.rts.trip.application.port.out.TripRepository
import veeci.practicing.rts.trip.domain.ArrivalState
import veeci.practicing.rts.trip.domain.PresenceState
import veeci.practicing.rts.trip.domain.Trip
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripMomentRaisedV1
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Moments, rule state and auto-arrive against Postgres: what in-memory fakes cannot show. */
@Testcontainers(disabledWithoutDocker = true)
class TripMomentsIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }
    private val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    private val trips = ExposedTripRepository()
    private val states = ExposedRuleStateStore()
    private val moments = ExposedTripMomentRepository()
    private val outbox = ExposedTripEventOutbox(OutboxWriter())
    private val driver = TripSamples.driver()

    @AfterEach
    fun closePool() = dataSource.close()

    private fun service(repository: TripRepository = trips) =
        TripEventService(
            repository,
            states,
            moments,
            outbox,
            TrackingPresence(ExposedTrackingSessionRepository(), transactions),
            transactions,
            Clock.systemUTC(),
        )

    private suspend fun acceptedTrip(): Trip =
        TripSamples.inStatus(TripStatus.ACCEPTED, driver = driver).also {
            transactions.inTransaction { trips.insert(it) }
        }

    private fun batch(vararg meters: Double) =
        FixesAccepted(
            UUID.randomUUID(),
            "phone-1",
            driver.userId.value,
            1,
            meters.mapIndexed {
                i,
                m,
                ->
                FixesAccepted.Position(GeoPoint(PICKUP.lat - m / 111_195.0, PICKUP.lng), T0 + (i * 5).seconds, 8.0, 0.0, 0.0)
            },
        )

    @Test
    fun `TC-4-PER-02 a stored moment keeps its kind and its payload reads back as the same moment`() =
        runTest {
            val moment = TripMoment.DriverArriving(UUID.randomUUID(), UUID.randomUUID(), TripStatus.ACCEPTED, T0, remainingM = 184.25)

            transactions.inTransaction { moments.append(moment) }

            val (kind, payload) =
                query("SELECT kind, payload::text FROM trip_moments WHERE trip_id = '${moment.tripId}'") {
                    it.getString(1) to
                        it.getString(2)
                }.single()
            kind shouldBe "DRIVER_ARRIVING"
            Json.decodeFromString(TripMomentRaisedV1.serializer(), payload).toMoment() shouldBe moment
        }

    @Test
    fun `a trip's arrival state starts empty and reads back as it was saved`() =
        runTest {
            val trip = acceptedTrip()
            val saved = ArrivalState(near = 2, far = 0, announced = true, atPickup = 1, slowSince = T0, lastAt = T0 + 5.seconds)

            transactions.inTransaction { states.lockArrival(trip.id) } shouldBe ArrivalState()
            transactions.inTransaction {
                states.lockArrival(trip.id)
                states.saveArrival(trip.id, saved)
            }

            transactions.inTransaction { states.lockArrival(trip.id) } shouldBe saved
        }

    @Test
    fun `TC-4-STL-07 presence state survives a new connection, so a restart raises no second stale`() =
        runTest {
            val trip = acceptedTrip()
            transactions.inTransaction {
                states.lockPresence(trip.id)
                states.savePresence(trip.id, PresenceState(stale = true))
            }
            val afterRestart = ExposedTransactionRunner(Database.connect(dataSource))

            afterRestart.inTransaction { ExposedRuleStateStore().lockPresence(trip.id) } shouldBe PresenceState(stale = true)
        }

    @Test
    fun `arriving and auto-arrive commit the moment, the new stage and both outbox rows together`() =
        runTest {
            val trip = acceptedTrip()

            service().onFixesAccepted(batch(250.0, 190.0, 180.0, 20.0, 10.0, 5.0))

            count("SELECT count(*) FROM trip_moments WHERE trip_id = '${trip.id.value}'") shouldBe 1
            count("SELECT count(*) FROM outbox WHERE payload->>'tripId' = '${trip.id.value}'") shouldBe 2
            transactions.inTransaction { trips.find(trip.id) }!!.status shouldBe TripStatus.ARRIVED
        }

    @Test
    fun `TC-4-PER-01 when saving the trip fails, no moment, no outbox row and no rule state is kept`() =
        runTest {
            val trip = acceptedTrip()
            val losesTheRace =
                object : TripRepository by trips {
                    override suspend fun update(trip: Trip) = false
                }

            shouldThrow<DomainException> { service(losesTheRace).onFixesAccepted(batch(190.0, 180.0, 20.0, 10.0, 5.0)) }

            count("SELECT count(*) FROM trip_moments WHERE trip_id = '${trip.id.value}'") shouldBe 0
            count("SELECT count(*) FROM outbox WHERE payload->>'tripId' = '${trip.id.value}'") shouldBe 0
            count("SELECT count(*) FROM trip_rule_state WHERE trip_id = '${trip.id.value}'") shouldBe 0
            transactions.inTransaction { trips.find(trip.id) }!!.status shouldBe TripStatus.ACCEPTED
        }

    private fun count(sql: String): Long = query(sql) { it.getLong(1) }.single()

    private fun <T> query(
        sql: String,
        row: (java.sql.ResultSet) -> T,
    ): List<T> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(row(rows)) } }
            }
        }
}
