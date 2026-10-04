package veeci.practicing.rts.platform.http

import io.ktor.http.HttpStatusCode
import veeci.practicing.rts.shared.ErrorCategory

/** Derives the public parts of a [Problem] from an error code: HTTP status, type and title. */
object ErrorCatalog {
    fun status(category: ErrorCategory): HttpStatusCode =
        when (category) {
            ErrorCategory.NOT_FOUND -> HttpStatusCode.NotFound
            ErrorCategory.CONFLICT -> HttpStatusCode.Conflict
            ErrorCategory.INVALID -> HttpStatusCode.UnprocessableEntity
            ErrorCategory.FORBIDDEN -> HttpStatusCode.Forbidden
        }

    /** A stable identifier for the error type. A URN, because there is no public docs page to link to yet. */
    fun type(code: String): String = "urn:rts:error:" + code.lowercase().replace('_', '-')

    /** `TRIP_NOT_FOUND` → `Trip not found`. */
    fun title(code: String): String = code.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Failures raised by the platform itself (bad input, unknown URL, bugs) rather than by a business rule. */
enum class PlatformError(
    val status: HttpStatusCode,
) {
    MALFORMED_REQUEST(HttpStatusCode.BadRequest),
    NOT_FOUND(HttpStatusCode.NotFound),
    INTERNAL_ERROR(HttpStatusCode.InternalServerError),
}
