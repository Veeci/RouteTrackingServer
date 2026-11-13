package veeci.practicing.rts.trip.application

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.domain.RouteKind
import veeci.practicing.rts.trip.domain.TripId
import java.util.UUID
import kotlin.time.Instant

class RerouteWorkerTest {
    private fun request(n: Int) =
        RerouteRequest(
            TripId(UUID.randomUUID()),
            RouteKind.TRIP,
            n,
            GeoPoint(10.0, 106.0),
            GeoPoint(10.1, 106.1),
            Instant.fromEpochSeconds(0),
        )

    @Test
    fun `queued requests are handled in order, and one that throws does not stop the worker`() =
        runTest {
            val handled = mutableListOf<Int>()
            val worker =
                RerouteWorker({
                    handled += it.baseVersion
                    if (it.baseVersion == 2) error("planner bug")
                    RerouteOutcome.REROUTED
                })
            worker.start(backgroundScope)

            (1..3).forEach { worker.request(request(it)) shouldBe true }
            testScheduler.runCurrent() // advanceUntilIdle ignores background work

            handled shouldBe listOf(1, 2, 3)
        }

    @Test
    fun `when the queue is full the oldest request is dropped`() =
        runTest {
            val handled = mutableListOf<Int>()
            val worker =
                RerouteWorker({
                    handled += it.baseVersion
                    RerouteOutcome.REROUTED
                }, capacity = 2)

            (1..3).forEach { worker.request(request(it)) }
            worker.start(backgroundScope)
            testScheduler.runCurrent() // advanceUntilIdle ignores background work

            handled shouldBe listOf(2, 3)
        }

    @Test
    fun `after stop nothing more is handled`() =
        runTest {
            val handled = mutableListOf<Int>()
            val worker =
                RerouteWorker({
                    handled += it.baseVersion
                    RerouteOutcome.REROUTED
                })
            worker.start(backgroundScope)

            worker.stop()
            worker.request(request(1))
            testScheduler.runCurrent() // advanceUntilIdle ignores background work

            handled shouldBe emptyList()
        }
}
