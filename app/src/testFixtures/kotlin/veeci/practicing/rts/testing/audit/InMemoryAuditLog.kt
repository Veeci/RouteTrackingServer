package veeci.practicing.rts.testing.audit

import veeci.practicing.rts.platform.audit.AuditEntry
import veeci.practicing.rts.platform.audit.AuditFilter
import veeci.practicing.rts.platform.audit.AuditLog
import veeci.practicing.rts.platform.audit.AuditRecord
import java.util.concurrent.ConcurrentLinkedQueue

/** Keeps the entries in order, for assertions. [find] is not needed by service tests. */
class InMemoryAuditLog : AuditLog {
    val entries = ConcurrentLinkedQueue<AuditEntry>()

    override suspend fun record(entry: AuditEntry) {
        entries += entry
    }

    override suspend fun find(
        filter: AuditFilter,
        before: Long?,
        limit: Int,
    ): List<AuditRecord> = error("Use ExposedAuditLog to query")
}
