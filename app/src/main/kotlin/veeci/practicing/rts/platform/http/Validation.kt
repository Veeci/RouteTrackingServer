package veeci.practicing.rts.platform.http

import kotlinx.serialization.Serializable

/**
 * A request body that checks its own fields. Checking runs automatically inside `call.receive<T>()`, so a
 * handler can never forget it, and the handler only ever sees valid input.
 */
interface Validatable {
    /** One entry per broken rule; empty when the body is valid. Reports every problem, not just the first. */
    fun validate(): List<FieldError>
}

@Serializable
data class FieldError(
    /** The JSON field, as the client sent it (`seats`, `stops[2].lat`). */
    val field: String,
    val message: String,
)

class RequestInvalidException(
    val errors: List<FieldError>,
) : RuntimeException("Request body failed validation: ${errors.joinToString { it.field }}")
