package veeci.practicing.rts.platform.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

/**
 * Optimistic concurrency over HTTP. A response carries the resource's version as a strong ETag (`"3"`). A client that
 * changes the resource may send it back in `If-Match`; if the resource changed in between, the service refuses with
 * 412 instead of overwriting someone else's change.
 */
fun etag(version: Long): String = "\"$version\""

/** The version in the request's `If-Match` header; null when the header is absent or `*` (any version). */
fun ApplicationCall.ifMatchVersion(): Long? {
    val value = request.headers[HttpHeaders.IfMatch]?.trim()
    if (value == null || value == "*") return null
    return value.removeSurrounding("\"").toLongOrNull()
        ?: throw RequestInvalidException(listOf(FieldError(HttpHeaders.IfMatch, "must be an ETag from this API, like \"3\"")))
}
