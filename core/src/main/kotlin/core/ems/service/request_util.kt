package core.ems.service

import core.exception.InvalidRequestException
import core.exception.ReqError
import javax.servlet.http.HttpServletRequest


fun String.idToLongOrInvalidReq(): Long = this.toLongOrNull() ?: throw InvalidRequestException(
    "No entity with id $this",
    ReqError.ENTITY_WITH_ID_NOT_FOUND, "id" to this
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
