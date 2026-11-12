package veeci.practicing.rts.platform.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import veeci.practicing.rts.platform.db.TransactionRunner
import kotlin.time.Duration

/** A task the server runs every [period]. With several instances, each run happens on one of them only. */
interface ScheduledJob {
    val name: String

    /** The key of this job's Postgres advisory lock. Unique per job. */
    val lockKey: Long
    val period: Duration

    suspend fun run()
}

/** Runs a block only when no other instance is running the same job right now. */
interface JobLock {
    /** Runs [block] and returns true, or returns false at once when another instance holds [key]. */
    suspend fun runIfFree(
        key: Long,
        block: suspend () -> Unit,
    ): Boolean
}

/**
 * [JobLock] with `pg_try_advisory_xact_lock` inside one transaction. Postgres releases the lock when the
 * transaction ends, so a failed or killed run cannot leave it held. A session-level lock
 * (`pg_try_advisory_lock`) would stay on the pooled connection if the unlock were skipped, and stop the job on
 * every instance. [block] runs in the same transaction.
 */
class PostgresJobLock(
    private val transactions: TransactionRunner,
) : JobLock {
    override suspend fun runIfFree(
        key: Long,
        block: suspend () -> Unit,
    ): Boolean =
        transactions.inTransaction {
            val acquired =
                TransactionManager.current().exec("SELECT pg_try_advisory_xact_lock($key)") { rows ->
                    rows.next() && rows.getBoolean(1)
                } ?: false
            if (acquired) block()
            acquired
        }
}

/**
 * Runs each [ScheduledJob] every period, from start until [stop]. A run that fails is logged and the next run
 * happens on time; one failure never stops a job. Graceful shutdown calls [stop] before the pool closes.
 */
class JobScheduler(
    private val lock: JobLock,
) {
    private val log = LoggerFactory.getLogger(JobScheduler::class.java)

    @Volatile
    private var running: List<Job> = emptyList()

    /** True while at least one job loop runs. */
    val isRunning: Boolean get() = running.any { it.isActive }

    fun start(
        scope: CoroutineScope,
        jobs: List<ScheduledJob>,
    ) {
        running = jobs.map { job -> scope.launch(CoroutineName("job-${job.name}")) { loop(job) } }
    }

    /** Stops every job and waits until a run in progress has finished. */
    suspend fun stop() {
        running.forEach { it.cancelAndJoin() }
    }

    @Suppress("TooGenericExceptionCaught") // whatever a run throws, the job must keep its schedule
    private suspend fun CoroutineScope.loop(job: ScheduledJob) {
        while (isActive) {
            delay(job.period)
            try {
                lock.runIfFree(job.lockKey) { job.run() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Job {} failed; it runs again in {}", job.name, job.period, e)
            }
        }
    }
}
