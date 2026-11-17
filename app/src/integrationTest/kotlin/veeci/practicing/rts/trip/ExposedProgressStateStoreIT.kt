package veeci.practicing.rts.trip

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.shared.geo.MeasuredPolyline
import veeci.practicing.rts.shared.geo.PolylineCodec
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.trip.TripSamples
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedProgressStateStore
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRepository
import veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRouteRepository
import veeci.practicing.rts.trip.application.port.out.FollowedRoute
import veeci.practicing.rts.trip.application.port.out.ProgressState
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.TripRoute
import veeci.practicing.rts.trip.domain.progress.MatchState
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Progress state in Postgres (phase 10a): what one instance writes, another reads back, route place included. */
@Testcontainers(disabledWithoutDocker = true)
class ExposedProgressStateStoreIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }
    private val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    private val routes = ExposedTripRouteRepository()
    private val at = Instant.parse("2026-11-17T08:00:00.123Z")

    @AfterEach
    fun closePool() = dataSource.close()

    @Test
    fun `TC-10-PRG-01 a state written by one instance reads back on another, with the route rebuilt from trip_routes`() =
        runTest {
            val trip = TripSamples.requested()
            val route = TripRoute(trip.id, RouteKind.TRIP, 2, "_p~iF~ps|U_ulLnnqC", 1_234.5, 300.25, trip.createdAt)
            transactions.inTransaction {
                ExposedTripRepository().insert(trip)
                routes.insert(route)
            }
            val followed =
                FollowedRoute(
                    RouteKind.TRIP,
                    2,
                    MeasuredPolyline(PolylineCodec.decode(route.polyline)),
                    1_234.5,
                    300.25,
                    MatchState(cursor = 1, alongM = 42.5),
                    offRouteCount = 2,
                )
            val state = ProgressState(8.25, GeoPoint(10.7725, 106.698), at, followed, lastRerouteAt = at - 30.seconds)

            transactions.inTransaction { ExposedProgressStateStore(routes).put(trip.id, state) }
            val read = transactions.inTransaction { ExposedProgressStateStore(routes).get(trip.id) }!! // a fresh cache

            read.copy(route = null) shouldBe state.copy(route = null)
            val back = read.route!!
            listOf(back.kind, back.version, back.distanceM, back.durationS, back.match, back.offRouteCount) shouldBe
                listOf(followed.kind, followed.version, followed.distanceM, followed.durationS, followed.match, followed.offRouteCount)
            back.line.points shouldBe followed.line.points
        }

    @Test
    fun `a removed state is gone, and a state without a route stays without one`() =
        runTest {
            val trip = TripSamples.requested()
            transactions.inTransaction { ExposedTripRepository().insert(trip) }
            val store = ExposedProgressStateStore(routes)
            val state = ProgressState(null, GeoPoint(10.7725, 106.698), at)

            transactions.inTransaction { store.put(trip.id, state) }
            transactions.inTransaction { store.get(trip.id) } shouldBe state

            transactions.inTransaction { store.remove(trip.id) }
            transactions.inTransaction { store.get(trip.id) }.shouldBeNull()
        }
}
