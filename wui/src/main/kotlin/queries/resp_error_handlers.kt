package queries

import Str
import debug
import errorMessage
import getContainer
import kotlinx.serialization.Serializable
import org.w3c.fetch.Response
import tmRender
import truncate
import kotlin.js.Promise


typealias RespSuccessChecker = Response.() -> Boolean
typealias RespErrorHandler = Response.(ErrorBody?) -> Boolean


object ErrorHandlers {

    val noCourseAccessPage: RespErrorHandler = { errorBody ->
        errorBody.handleByCode(RespError.NO_COURSE_ACCESS) {
            debug { "Error handled by no course access page handler" }
            getContainer().innerHTML = tmRender(
                "tm-no-access-page", mapOf(
                    "title" to Str.noCourseAccessPageTitle(),
                    "msg" to Str.noCourseAccessPageMsg()
                )
            )
        }
    }

    val noVisibleExerciseMsg: RespErrorHandler =
        noEntityFoundMessage("Seda ülesannet ei eksisteeri või see on peidetud")

    fun noEntityFoundMessage(msg: String): RespErrorHandler = { errorBody ->
        errorBody.handleByCode(RespError.ENTITY_WITH_ID_NOT_FOUND) {
            debug { "Error handled by no entity found message" }
            errorMessage { msg }
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
    ACCOUNT_EMAIL_NOT_FOUND("ACCOUNT_EMAIL_NOT_FOUND"),
    NO_COURSE_ACCESS("NO_COURSE_ACCESS"),
    NO_GROUP_ACCESS("NO_GROUP_ACCESS"),
    GROUP_NOT_EMPTY("GROUP_NOT_EMPTY"),
    ENTITY_WITH_ID_NOT_FOUND("ENTITY_WITH_ID_NOT_FOUND"),
    EXERCISE_ALREADY_ON_COURSE("EXERCISE_ALREADY_ON_COURSE"),

    ACCOUNT_MIGRATION_FAILED("ACCOUNT_MIGRATION_FAILED"),
}

@Serializable
data class ErrorBody(
    val id: String,
    val code: String? = null,
    val attrs: Map<String, String>,
    val log_msg: String
)

/**
 * Used to indicate that an erroneous (i.e. not successful) response was obtained from a fetch, but it was handled by an error handler.
 * @param errorHandlerException - an exception from the error handler if one was thrown by the handler
 */
class HandledResponseError(val errorHandlerException: Promise<Throwable>) : Exception()


fun ErrorBody?.handleByCode(respError: RespError, errorHandler: (ErrorBody) -> Unit): Boolean =
    if (this != null && this.code == respError.code) {
        errorHandler(this)
        true
    } else false

fun Response.handleAlways(errorHandler: (Response) -> Unit): Boolean =
    true.also { errorHandler(this) }
