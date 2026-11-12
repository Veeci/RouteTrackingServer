package veeci.practicing.rts.platform.jobs

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase

@Testcontainers(disabledWithoutDocker = true)
class PostgresJobLockIT {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.env()).db)

    /** Two instances: separate transaction runners over separate pools, like two servers. */
    private val instanceA = PostgresJobLock(ExposedTransactionRunner(Database.connect(dataSource)))
    private val otherPool = createDataSource(TestConfig.load(TestDatabase.env()).db)
    private val instanceB = PostgresJobLock(ExposedTransactionRunner(Database.connect(otherPool)))
    private val key = System.nanoTime()

    @AfterEach
    fun closePools() {
        dataSource.close()
        otherPool.close()
    }

    @Test
    fun `TC-4-STL-06 while one instance runs the job, the other skips it, and the lock is free again after`(): Unit =
        runBlocking {
            val running = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first =
                async(Dispatchers.IO) {
                    instanceA.runIfFree(key) {
                        running.complete(Unit)
                        release.await()
                    }
                }
            running.await()

            withContext(Dispatchers.IO) { instanceB.runIfFree(key) { error("must not run") } } shouldBe false

            release.complete(Unit)
            first.await() shouldBe true
            var ranAfter = false
            withContext(Dispatchers.IO) { instanceB.runIfFree(key) { ranAfter = true } } shouldBe true
            ranAfter shouldBe true
        }

    @Test
    fun `a run that fails releases the lock, because it is a transaction lock`(): Unit =
        runBlocking {
            runCatching { instanceA.runIfFree(key) { error("boom") } }

            instanceB.runIfFree(key) {} shouldBe true
        }
}
