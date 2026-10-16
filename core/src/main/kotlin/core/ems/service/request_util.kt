package core.ems.service

import core.exception.InvalidRequestException
import core.exception.ReqError
import jakarta.servlet.http.HttpServletRequest


/**
 * A client's id that is not a number is the client's mistake, refused like any other entity it names
 * wrongly - and not an operator alert. With the default `notify = true` every such request also mailed
 * ops a stack trace, synchronously and without a limit, which any caller could repeat at will.
 */
fun String.idToLongOrInvalidReq(): Long = this.toLongOrNull() ?: throw InvalidRequestException(
    "No entity with id $this",
    ReqError.ENTITY_WITH_ID_NOT_FOUND, "id" to this,
    notify = false,
)

fun <T> List<T>.singleOrInvalidRequest(): T {
    return this.singleOrNull() ?: throw InvalidRequestException(
        "Entity not found", ReqError.ENTITY_WITH_ID_NOT_FOUND
    )
}

fun <T> List<T>.singleOrInvalidRequest(notify: Boolean): T {
    return this.singleOrNull() ?: throw InvalidRequestException(
        "Entity not found", ReqError.ENTITY_WITH_ID_NOT_FOUND, notify = notify
    )
}

fun HttpServletRequest.getOptionalHeader(headerName: String): String? {
    val headerValue: String? = this.getHeader(headerName)
    return if (headerValue.isNullOrBlank()) null else headerValue
}
