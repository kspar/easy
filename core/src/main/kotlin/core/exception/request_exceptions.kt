package core.exception


abstract class AccessControlException(message: String) : RuntimeException(message)

class ForbiddenException(
    override val message: String,
    val code: ReqError,
    vararg val attributes: Pair<String, String>
) : AccessControlException(message)

class InvalidRequestException(
    override val message: String,
    val code: ReqError? = null,
    vararg val attributes: Pair<String, String>,
    val notify: Boolean = true
) : RuntimeException(message)
