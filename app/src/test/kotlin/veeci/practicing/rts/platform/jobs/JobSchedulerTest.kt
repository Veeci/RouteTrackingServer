package veeci.practicing.rts.platform.jobs

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class JobSchedulerTest {
    /** A lock that is free, or held by another instance. */
    private class FakeLock(
        var free: Boolean = true,
    ) : JobLock {
        override suspend fun runIfFree(
            key: Long,
            block: suspend () -> Unit,
        ): Boolean {
            if (free) block()
            return free
        }
    }

    private class CountingJob(
        override val period: Duration = 10.seconds,
        private val fail: Boolean = false,
    ) : ScheduledJob {
        override val name = "counting"
        override val lockKey = 1L
        var runs = 0

        override suspend fun run() {
            runs++
            if (fail) error("this run fails")
        }
    }

    private fun TestScope.started(
        job: ScheduledJob,
        lock: JobLock = FakeLock(),
    ) = JobScheduler(lock).also { it.start(backgroundScope, listOf(job)) }

    @Test
    fun `TC-4-STL-04 a 10 s job runs 3 times in 35 s of virtual time`() =
        runTest {
            val job = CountingJob()
            started(job)

            advanceTimeBy(35.seconds)

            job.runs shouldBe 3
        }

    @Test
    fun `TC-4-STL-05 after stop the job runs no more`() =
        runTest {
            val job = CountingJob()
            val scheduler = started(job)
            advanceTimeBy(15.seconds)

            scheduler.stop()
            advanceTimeBy(60.seconds)

            job.runs shouldBe 1
            scheduler.isRunning shouldBe false
        }

    @Test
    fun `a failing run is logged and the job keeps its schedule`() =
        runTest {
            val job = CountingJob(fail = true)
            started(job)

            advanceTimeBy(35.seconds)

            job.runs shouldBe 3
        }

    @Test
    fun `while another instance holds the lock the job does not run here`() =
        runTest {
            val job = CountingJob()
            val lock = FakeLock(free = false)
            started(job, lock)

            advanceTimeBy(25.seconds)
            job.runs shouldBe 0

            lock.free = true
            advanceTimeBy(10.seconds)
            runCurrent()
            job.runs shouldBe 1
        }
}
