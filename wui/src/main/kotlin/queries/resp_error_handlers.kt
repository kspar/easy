package queries

import Str
import debug
import errorMessage
import getContainer
import kotlinx.serialization.Serializable
import org.w3c.fetch.Response
import tmRender
import truncate


typealias RespSuccessChecker = Response.() -> Boolean
typealias RespErrorHandler = Response.(ErrorBody?) -> Boolean


object ErrorHandlers {

    val noCourseAccessPage: RespErrorHandler = { errorBody ->
        errorBody.handleByCode(RespError.NO_COURSE_ACCESS) {
            debug { "Error handled by no course access page handler" }
            getContainer().innerHTML = tmRender("tm-no-access-page", mapOf(
                    "title" to Str.noCourseAccessPageTitle(),
                    "msg" to Str.noCourseAccessPageMsg()
            ))
        }
    }

    val defaultMsg: RespErrorHandler = { errorBody ->
        debug { "Error handled by default message handler" }
        val status = this.status
        if (errorBody == null) {
            this.text().then { body ->
                errorMessage {
                    """Midagi läks valesti, palun proovi hiljem uuesti. 
                    |Server tagastas ootamatu vastuse:
                    |HTTP staatus: $status
                    |Vastus: ${body.truncate(150)}
                """.trimMargin()
                }
            }.catch {
                errorMessage { "Midagi läks valesti, palun proovi hiljem uuesti. Server tagastas ootamatu vastuse HTTP staatusega $status." }
            }
        } else {
            errorMessage {
                """Midagi läks valesti, palun proovi hiljem uuesti. 
                |Server tagastas vea:
                |HTTP staatus: $status
                |Kood: ${errorBody.code}
                |Sõnum: ${errorBody.log_msg}
            """.trimMargin()
            }
        }
        true
    }
}

enum class RespError(val code: String) {
    NO_COURSE_ACCESS("NO_COURSE_ACCESS"),
    GROUP_NOT_EMPTY("GROUP_NOT_EMPTY"),
}

@Serializable
data class ErrorBody(
        val id: String,
        val code: String? = null,
        val attrs: Map<String, String>,
        val log_msg: String
)

/**
 * Used to indicate that an erroneous (i.e. not successful) response was obtained from a fetch but it was handled by an error handler.
 */
class HandledResponseError : Exception()


fun ErrorBody?.handleByCode(respError: RespError, errorHandler: (ErrorBody) -> Unit): Boolean =
        if (this != null && this.code == respError.code) {
            errorHandler(this)
            true
        } else false

fun Response.handleAlways(errorHandler: (Response) -> Unit): Boolean =
        true.also { errorHandler(this) }
