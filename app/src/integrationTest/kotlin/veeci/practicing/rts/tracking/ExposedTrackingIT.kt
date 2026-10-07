package veeci.practicing.rts.tracking

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
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
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.tracking.TrackingPersistenceContract
import veeci.practicing.rts.testing.tracking.TrackingSamples.T0
import veeci.practicing.rts.testing.tracking.TrackingSamples.fullFix
import veeci.practicing.rts.testing.tracking.TrackingSamples.newBatch
import veeci.practicing.rts.testing.tracking.TrackingSamples.reading
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedFixBatchRepository
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingEventOutbox
import veeci.practicing.rts.tracking.adapter.out.persistence.ExposedTrackingSessionRepository
import veeci.practicing.rts.tracking.application.IngestBatch
import veeci.practicing.rts.tracking.application.OpenSession
import veeci.practicing.rts.tracking.application.TrackingService
import veeci.practicing.rts.tracking.application.port.out.InsertResult
import veeci.practicing.rts.tracking.domain.FixPipeline
import veeci.practicing.rts.tracking.domain.PipelineConfig
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant

/** The Postgres repositories pass the tracking contract, plus what only a real database can show. */
@Testcontainers(disabledWithoutDocker = true)
class ExposedTrackingIT : TrackingPersistenceContract() {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }

    override val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    override val sessions = ExposedTrackingSessionRepository()
    override val batches = ExposedFixBatchRepository()

    @AfterEach
    fun closePool() = dataSource.close()

    @Test
    fun `TC-2-DB-03 two concurrent inserts of the same seq store it once`() =
        runTest {
            tx { sessions.save(session) }
            val batch = newBatch(session.id, 1, accepted = listOf(fullFix(T0), fullFix(T0 + 1.seconds, index = 1)))

            val results =
                coroutineScope {
                    List(2) { async(Dispatchers.IO) { transactions.inTransaction { batches.insert(batch) } } }.awaitAll()
                }

            results.count { it == InsertResult.Inserted } shouldBe 1
            results.count { it is InsertResult.AlreadyExists } shouldBe 1
            count("SELECT count(*) FROM fixes") shouldBe 2
        }

    @Test
    fun `TC-2-OUT-01 a committed ingest writes its outbox row, a rolled back one writes nothing`() =
        runTest {
            val service =
                TrackingService(
                    sessions,
                    batches,
                    ExposedTrackingEventOutbox(OutboxWriter()),
                    transactions,
                    FixPipeline(PipelineConfig()),
                    Clock.fixed((T0 + 1.minutes).toJavaInstant(), ZoneOffset.UTC),
                    maxFixesPerBatch = 100,
                )
            service.openSession(OpenSession(session.id, session.deviceId, sdkVersion = "0.9.0"))

            service.ingest(IngestBatch(session.id, 1, listOf(reading(T0))))
            shouldThrow<IllegalStateException> {
                transactions.inTransaction {
                    service.ingest(IngestBatch(session.id, 2, listOf(reading(T0 + 1.seconds))))
                    error("the surrounding transaction fails after the ingest finished")
                }
            }

            count("SELECT count(*) FROM outbox WHERE event_type = 'tracking.FixesAccepted.v1' AND payload->>'seq' = '1'") shouldBe 1
            count("SELECT count(*) FROM outbox") shouldBe 1
            tx { batches.find(session.id, 2) }.shouldBeNull()
        }

    private fun count(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }
}
