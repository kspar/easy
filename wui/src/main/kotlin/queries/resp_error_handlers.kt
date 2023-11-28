package queries

import components.ToastId
import components.ToastIds
import components.ToastThing
import debug
import kotlinx.serialization.Serializable
import org.w3c.fetch.Response
import pages.courses.CoursesPage
import rip.kspar.ezspa.EzSpa
import translation.Str
import truncate
import kotlin.js.Promise


typealias RespSuccessChecker = Response.() -> Boolean
typealias RespErrorHandler = Response.(ErrorBody?) -> Boolean


object ErrorHandlers {

    val noCourseAccessMsg: RespErrorHandler = { errorBody ->
        errorBody.handleByCode(RespError.NO_COURSE_ACCESS) {
            EzSpa.PageManager.navigateTo(CoursesPage.link())
            ToastThing(
                Str.noCourseAccessPageMsg, icon = ToastThing.ERROR, displayTime = ToastThing.LONG,
                id = ToastIds.noCourseAccess
            )
        }
    }

    val noVisibleExerciseMsg: RespErrorHandler =
        noEntityFoundMessage(Str.noVisibleExerciseError, ToastIds.noVisibleCourseExercise)

    fun noEntityFoundMessage(msg: String, toastId: ToastId): RespErrorHandler = { errorBody ->
        errorBody.handleByCode(RespError.ENTITY_WITH_ID_NOT_FOUND) {
            ToastThing(msg, icon = ToastThing.ERROR, displayTime = ToastThing.LONG, id = toastId)
        }
    }

    val defaultMsg: RespErrorHandler = { errorBody ->
        debug { "Error handled by default message handler" }
        val status = this.status
        if (errorBody == null) {
            this.text().then { body ->
                ToastThing(
                    Str.translateServerError(status, null, body.truncate(150)),
                    icon = ToastThing.ERROR, displayTime = ToastThing.LONG
                )
            }.catch {
                ToastThing(
                    Str.serverErrorMsg + " " + status,
                    icon = ToastThing.ERROR, displayTime = ToastThing.LONG
                )
            }
        } else {
            ToastThing(
                Str.translateServerError(status, errorBody.code, errorBody.log_msg),
                icon = ToastThing.ERROR, displayTime = ToastThing.LONG
            )
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
    NO_EXERCISE_ACCESS("NO_EXERCISE_ACCESS"),
    COURSE_EXERCISE_CLOSED("COURSE_EXERCISE_CLOSED"),

    ACCOUNT_MIGRATION_FAILED("ACCOUNT_MIGRATION_FAILED"),

    TSL_COMPILE_FAILED("TSL_COMPILE_FAILED"),
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
