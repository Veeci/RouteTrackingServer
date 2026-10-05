package veeci.practicing.rts.platform.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Runs [block] as one database transaction: every write inside commits together or rolls back together.
 * Application services wrap each use case in it (they are the transaction boundary). A call made while a
 * transaction is already open joins that transaction instead of starting a second one.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

class ExposedTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    // JDBC blocks its thread while waiting for Postgres, so the work moves to the IO pool and never
    // occupies the threads that serve HTTP and WebSocket traffic.
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) { block() }
        }
}
