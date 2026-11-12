package veeci.practicing.rts.devicecontrol

import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.devicecontrol.adapter.out.persistence.ExposedDeviceCommandRepository
import veeci.practicing.rts.platform.db.ExposedTransactionRunner
import veeci.practicing.rts.platform.db.Migrations
import veeci.practicing.rts.platform.db.createDataSource
import veeci.practicing.rts.testing.TestConfig
import veeci.practicing.rts.testing.TestDatabase
import veeci.practicing.rts.testing.devicecontrol.DeviceCommandRepositoryContract

/** The Postgres command repository passes the same contract as the in-memory one (TC-5-DB-01). */
@Testcontainers(disabledWithoutDocker = true)
class ExposedDeviceCommandIT : DeviceCommandRepositoryContract() {
    private val dataSource = createDataSource(TestConfig.load(TestDatabase.freshDatabaseEnv()).db).also { Migrations.run(it) }

    override val transactions = ExposedTransactionRunner(Database.connect(dataSource))
    override val commands = ExposedDeviceCommandRepository()

    @AfterEach
    fun closePool() = dataSource.close()
}
