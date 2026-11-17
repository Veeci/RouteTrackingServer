package veeci.practicing.rts.platform.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Runs [block] as one database transaction: every write inside commits together or rolls back together.
 * Application services wrap each use case in it (they are the transaction boundary). A call made while a
 * transaction is already open joins that transaction instead of starting a second one.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/**
 * [maxConnections] is the size of [database]'s pool (null: no limit, for tests). A coroutine waits for a permit
 * before it asks the pool for a connection. Waiting for a permit suspends and holds no thread. Waiting inside
 * Hikari blocks a thread of [Dispatchers.IO] (64 threads), and at 1 000 drivers 53 of them were blocked there
 * (load check 11), so a transaction that held a connection could find no thread to finish on.
 */
class ExposedTransactionRunner(
    private val database: Database,
    maxConnections: Int? = null,
) : TransactionRunner {
    private val permits = maxConnections?.let { Semaphore(it) }

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        // Join whichever transaction is open, even one of another pool: the outbox relay opens its transaction
        // on its own pool, and the handlers it calls must write in that transaction, not in a second one.
        if (TransactionManager.currentOrNull() != null) return block()
        // Not `permits?.withPermit { } ?: open(block)`: a block that returns null would then run twice.
        val limit = permits
        return if (limit == null) open(block) else limit.withPermit { open(block) }
    }

    // JDBC blocks its thread while waiting for Postgres, so the work moves to the IO pool and never
    // occupies the threads that serve HTTP and WebSocket traffic.
    private suspend fun <T> open(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) { block() }
        }
}
