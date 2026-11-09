package veeci.practicing.rts.platform.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.json.jsonb
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.DomainException
import veeci.practicing.rts.shared.ErrorCategory
import veeci.practicing.rts.shared.ErrorCode
import java.security.MessageDigest
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinInstant

const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/** A response as it was sent, so that it can be sent again unchanged. */
data class StoredResponse(
    val status: HttpStatusCode,
    val headers: Map<String, String>,
    /** JSON. */
    val body: String,
)

enum class IdempotencyError(
    override val category: ErrorCategory,
) : ErrorCode {
    /** The key was used before for a different request (another body or path). Use a new key for a new request. */
    IDEMPOTENCY_KEY_REUSED(ErrorCategory.INVALID),
}

/** The `idempotency_keys` table (migration V7). */
internal object IdempotencyKeysTable : Table("idempotency_keys") {
    val principal = text("principal")
    val key = text("key")
    val requestHash = text("request_hash")
    val status = integer("status")
    val headers = jsonb("headers", Json, MapSerializer(String.serializer(), String.serializer()))
    val body = text("body")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(principal, key)
}

/**
 * Makes a request safe to repeat. The client sends a new random `Idempotency-Key` for each user action (one tap on
 * "Book"). The first request runs; a retry with the same key and the same request gets the first response again
 * and changes nothing. The same key with a different request gets 422 IDEMPOTENCY_KEY_REUSED.
 *
 * The handler runs in the same transaction as the stored response (the service's transaction joins it), so either
 * both commit or neither does. Two requests with the same key at the same moment: the second one waits on the
 * primary key, fails when the first commits, rolls back its own work, and answers with the first response.
 */
class Idempotency(
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val ttl: Duration = 24.hours,
) {
    /**
     * Runs [handle] unless this [principal] already used [key] in the last [ttl]. Without a key it just runs
     * [handle]. [request] must identify the request completely (method, path, canonical body).
     */
    suspend fun execute(
        key: String?,
        principal: String,
        request: String,
        handle: suspend () -> StoredResponse,
    ): Replayable {
        if (key == null) return Replayable(handle(), replayed = false)
        if (key.isBlank() || key.length > MAX_KEY_LENGTH) {
            throw RequestInvalidException(listOf(FieldError(IDEMPOTENCY_KEY_HEADER, "must be 1 to $MAX_KEY_LENGTH characters")))
        }
        val hash = sha256(request)
        return try {
            transactions.inTransaction {
                forgetExpired(principal, key)
                find(principal, key)?.replay(hash) ?: Replayable(handle().also { store(principal, key, hash, it) }, replayed = false)
            }
        } catch (e: ExposedSQLException) {
            if (e.sqlState != UNIQUE_VIOLATION) throw e
            // A request with the same key committed first while this one ran. This one was rolled back.
            transactions.inTransaction { checkNotNull(find(principal, key)) { "Stored response for $key vanished" }.replay(hash) }
        }
    }

    private fun forgetExpired(
        principal: String,
        key: String,
    ) {
        val expiredBefore = clock.instant().toKotlinInstant() - ttl
        IdempotencyKeysTable.deleteWhere {
            (IdempotencyKeysTable.principal eq principal) and (IdempotencyKeysTable.key eq key) and
                (IdempotencyKeysTable.createdAt lessEq expiredBefore)
        }
    }

    private fun find(
        principal: String,
        key: String,
    ): Stored? =
        IdempotencyKeysTable
            .selectAll()
            .where { (IdempotencyKeysTable.principal eq principal) and (IdempotencyKeysTable.key eq key) }
            .singleOrNull()
            ?.let {
                Stored(
                    it[IdempotencyKeysTable.requestHash],
                    StoredResponse(
                        HttpStatusCode.fromValue(it[IdempotencyKeysTable.status]),
                        it[IdempotencyKeysTable.headers],
                        it[IdempotencyKeysTable.body],
                    ),
                )
            }

    private fun store(
        principal: String,
        key: String,
        hash: String,
        response: StoredResponse,
    ) {
        IdempotencyKeysTable.insert {
            it[IdempotencyKeysTable.principal] = principal
            it[IdempotencyKeysTable.key] = key
            it[requestHash] = hash
            it[status] = response.status.value
            it[headers] = response.headers
            it[body] = response.body
            it[createdAt] = clock.instant().toKotlinInstant()
        }
    }

    private class Stored(
        val requestHash: String,
        val response: StoredResponse,
    ) {
        fun replay(hash: String): Replayable {
            if (hash != requestHash) {
                throw DomainException(IdempotencyError.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used for a different request")
            }
            return Replayable(response, replayed = true)
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_KEY_LENGTH = 255
        const val UNIQUE_VIOLATION = "23505"
    }
}

/** A response, and whether it is a stored one sent again. */
data class Replayable(
    val response: StoredResponse,
    val replayed: Boolean,
)

/** Sends [replayable]; a replay is marked with `Idempotent-Replayed: true` so that clients and logs can tell. */
suspend fun ApplicationCall.respond(replayable: Replayable) {
    replayable.response.headers.forEach { (name, value) -> response.header(name, value) }
    if (replayable.replayed) response.header("Idempotent-Replayed", "true")
    respondText(replayable.response.body, ContentType.Application.Json, replayable.response.status)
}
