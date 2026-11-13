package veeci.practicing.rts.shared

/**
 * Identifies one kind of business failure. Each bounded context declares its codes as an enum implementing
 * this interface. [name] is public API: clients branch on it, so a released code is never renamed.
 */
interface ErrorCode {
    val name: String
    val category: ErrorCategory
}

/** What kind of failure it is, in business terms. Only the web layer turns it into an HTTP status. */
enum class ErrorCategory {
    /** The thing asked for does not exist, or the caller may not know that it exists. */
    NOT_FOUND,

    /** The request is well formed but clashes with the current state, e.g. ending a trip that already ended. */
    CONFLICT,

    /** The request breaks a business rule about its own content. */
    INVALID,

    /** The caller is known but not allowed to do this. */
    FORBIDDEN,

    /** The caller acted on an outdated copy (an old version); they read again and decide again. */
    STALE,

    /**
     * The request is fine but the server cannot answer it right now (a map still loading, a computation over its
     * time budget). Not the caller's fault: the same request may work a little later.
     */
    UNAVAILABLE,
}

/**
 * A business rule refused the operation. Expected, not a bug: it becomes a 4xx response carrying [code], and
 * its message is shown to the client, so it must never contain internals (SQL, class names, secrets).
 */
open class DomainException(
    val code: ErrorCode,
    message: String,
) : RuntimeException(message)
