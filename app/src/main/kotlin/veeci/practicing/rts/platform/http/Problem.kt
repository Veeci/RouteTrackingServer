package veeci.practicing.rts.platform.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one error body of this API, following RFC 9457 "Problem Details for HTTP APIs".
 * [code] and [traceId] are this API's own extension members.
 */
@Serializable
data class Problem(
    /** Identifies the error type; stable, the same for every occurrence. */
    val type: String,
    /** Short human summary of the type; stable. */
    val title: String,
    /** The HTTP status, repeated in the body for clients and logs that only keep the body. */
    val status: Int,
    /** What went wrong this time, for humans. */
    val detail: String? = null,
    /** The request path this problem is about. */
    val instance: String? = null,
    /** Machine-readable error code; what clients switch on. */
    val code: String,
    /** Correlates this response with the server's logs. */
    val traceId: String,
)

val ProblemContentType = ContentType("application", "problem+json")

private val problemJson = Json { explicitNulls = false }

suspend fun ApplicationCall.respondProblem(problem: Problem) =
    respondText(problemJson.encodeToString(problem), ProblemContentType, HttpStatusCode.fromValue(problem.status))
