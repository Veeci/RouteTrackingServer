package veeci.practicing.rts.trip

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.platform.events.OutboxWriter
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.routing.TestRouting
import veeci.practicing.rts.testing.trip.TripRepositoryContract
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripEventOutbox
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripMomentRepository
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRepository
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRouteRepository
import veeci.practicing.rts.trip.application.ActOnTrip
import veeci.practicing.rts.trip.application.RequestTrip
import veeci.practicing.rts.trip.application.RerouteOutcome
import veeci.practicing.rts.trip.application.RerouteRequest
import veeci.practicing.rts.trip.application.TripRouteService
import veeci.practicing.rts.trip.application.TripService
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.RouteReason
import veeci.practicing.rts.trip.domain.TripAction
import veeci.practicing.rts.trip.domain.TripError
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripRoute
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.time.Clock

/** The Postgres repository passes the trip contract, plus what only a real database can show. */
@Testcontainers(disabledWithoutDocker = true)
class ExposedTripIT : TripRepositoryContract() {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }

    override val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    override val trips = ExposedTripRepository()

    @AfterEach
    fun closePool() = dataSource.close()

    @Test
    fun `TC-3-DB-03 two drivers accept the same trip at once, and exactly one of them gets it`() =
        runTest {
            val service =
                TripService(
                    trips,
                    ExposedTripEventOutbox(OutboxWriter()),
                    transactions,
                    Clock.systemUTC(),
                    TestRouting.planner(),
                    ExposedTripRouteRepository(),
                )
            val trip = service.request(RequestTrip(TripSamples.PICKUP, TripSamples.DESTINATION), TripSamples.guest())

            val outcomes =
                coroutineScope {
                    List(2) {
                        async(Dispatchers.IO) {
                            runCatching { service.act(ActOnTrip(TripId(trip.id), TripAction.ACCEPT), TripSamples.driver()) }
                        }
                    }.awaitAll()
                }

            outcomes.count { it.isSuccess } shouldBe 1
            // The loser either saw the old version (CONCURRENT_MODIFICATION) or the accepted trip (ILLEGAL_TRANSITION).
            (outcomes.single { it.isFailure }.exceptionOrNull() as DomainException).code.category shouldBe
                TripError.CONCURRENT_MODIFICATION.category
            count("SELECT count(*) FROM outbox WHERE event_type = 'trip.TripStatusChanged.v1'") shouldBe 2 // requested + one accept
        }

    @Test
    fun `TC-6-API-04 trip_routes keeps every version and returns the newest of a kind`() =
        runTest {
            val routes = ExposedTripRouteRepository()
            val trip = TripSamples.requested()
            transactions.inTransaction { trips.insert(trip) }
            val first = TripRoute(trip.id, RouteKind.TRIP, 1, "_p~iF~ps|U", 1_234.5, 300.25, trip.createdAt)
            val second = first.copy(version = 2, distanceM = 1_300.0)

            transactions.inTransaction {
                routes.insert(first)
                routes.insert(second)
            }

            transactions.inTransaction { routes.findLatest(trip.id, RouteKind.TRIP) } shouldBe second
            transactions.inTransaction { routes.findLatest(trip.id, RouteKind.APPROACH) } shouldBe null
        }

    @Test
    fun `TC-7-DB-01 the reason round-trips, and a taken version is refused without failing the transaction`() =
        runTest {
            val routes = ExposedTripRouteRepository()
            val trip = TripSamples.requested()
            val first = TripRoute(trip.id, RouteKind.TRIP, 1, "_p~iF~ps|U", 1_234.5, 300.25, trip.createdAt)
            val rerouted = first.copy(version = 2, reason = RouteReason.OFF_ROUTE)

            val inserted =
                transactions.inTransaction {
                    trips.insert(trip)
                    routes.insert(first)
                    listOf(routes.insertIfAbsent(rerouted), routes.insertIfAbsent(rerouted.copy(distanceM = 9.0)))
                }

            inserted shouldBe listOf(true, false) // the transaction still committed after the refused insert
            transactions.inTransaction { routes.findLatest(trip.id, RouteKind.TRIP) } shouldBe rerouted
        }

    @Test
    fun `TC-7-RR-06 two re-routes of one version at once store one route and one event`() =
        runTest {
            val routes = ExposedTripRouteRepository()
            val trip = TripSamples.inStatus(TripStatus.IN_PROGRESS, driver = TripSamples.driver())
            transactions.inTransaction {
                trips.insert(trip)
                routes.insert(TripRoute(trip.id, RouteKind.TRIP, 1, "_p~iF~ps|U", 812.0, 81.2, trip.createdAt))
            }
            val service =
                TripRouteService(
                    trips,
                    routes,
                    ExposedTripMomentRepository(),
                    ExposedTripEventOutbox(OutboxWriter()),
                    TestRouting.planner(),
                    transactions,
                    Clock.systemUTC(),
                )
            val request =
                RerouteRequest(trip.id, RouteKind.TRIP, 1, TripSamples.PICKUP, TripSamples.DESTINATION, trip.updatedAt)

            val outcomes =
                coroutineScope { List(2) { async(Dispatchers.IO) { service.reroute(request) } }.awaitAll() }

            outcomes.sorted() shouldBe listOf(RerouteOutcome.REROUTED, RerouteOutcome.DROPPED)
            count("SELECT count(*) FROM trip_routes WHERE trip_id = '${trip.id.value}'") shouldBe 2
            count("SELECT count(*) FROM outbox WHERE event_type = 'trip.TripRouteChanged.v1'") shouldBe 1
            count("SELECT count(*) FROM trip_moments WHERE kind = 'OFF_ROUTE'") shouldBe 1
        }

    private fun count(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery(sql).use { it.next().let { _ -> it.getLong(1) } } }
        }
}
