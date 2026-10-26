package core.exception

import core.aas.ExecutorOverloadException
import core.conf.NulCharacterException
import core.util.SendMailService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.json.JsonParseException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.databind.exc.InvalidNullException
import tools.jackson.databind.exc.MismatchedInputException
import java.util.*

/**
 * A message that may carry request text, made safe to end a log line with.
 *
 * `idToLongOrInvalidReq` puts the raw id into its message, and a validation failure puts the rejected
 * value into its own, so a teacher could send an id like `1\n<timestamp> ERROR ...` and the entry
 * logged here would end at `1` and be followed by a forged line attributed to another component.
 * Escaping CR and LF keeps each exception on the one line it belongs to.
 */
private fun Any?.oneLine(): String = toString().replace("\r", "\\r").replace("\n", "\\n")

// TODO: check that exception handlers do not leak system information
@ControllerAdvice
class EasyExceptionHandler(private val mailService: SendMailService) : ResponseEntityExceptionHandler() {
    private val log = KotlinLogging.logger {}

    @ExceptionHandler(value = [ExecutorOverloadException::class])
    fun handleExecutorOverloadException(ex: ExecutorOverloadException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.warn { "ExecutorOverloadException $ex" }
        log.warn { "Request info: ${request.getDescription(true)}" }
        mailService.sendSystemNotification(ex.stackTraceString, id)
        // TODO: also use code
        return ResponseEntity(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @ExceptionHandler(value = [ForbiddenException::class])
    fun handleForbiddenException(ex: ForbiddenException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()

        log.warn { "Forbidden error: ${ex.message.oneLine()}, id: $id" }
        log.warn { "Request info: ${request.getDescription(true)}" }

        if (ex.notify) {
            mailService.sendSystemNotification(ex.stackTraceString, id)
        }

        val resp = RequestErrorResponse(id, ex.code.errorCodeStr, mapOf(*ex.attributes), ex.message)
        return ResponseEntity(resp, HttpStatus.FORBIDDEN)
    }

    @ExceptionHandler(value = [InvalidRequestException::class])
    fun handleInvalidReqException(ex: InvalidRequestException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()

        log.warn { "Invalid request, message: ${ex.message.oneLine()}, id: $id" }
        log.warn { "Request info: ${request.getDescription(true)}" }

        if (ex.notify) {
            mailService.sendSystemNotification(ex.stackTraceString, id)
        }

        val resp = RequestErrorResponse(id, ex.code?.errorCodeStr, mapOf(*ex.attributes), ex.message)
        return ResponseEntity(resp, HttpStatus.BAD_REQUEST)
    }

    @ExceptionHandler(value = [Exception::class])
    fun handleGenericException(ex: Exception, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.error { "Caught a big one ${ex.oneLine()}" }
        log.error { "Request info: ${request.getDescription(true)}" }
        mailService.sendSystemNotification(ex.stackTraceString, id)
        return ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @ExceptionHandler(value = [AccessDeniedException::class])
    fun handleAccessDeniedException(ex: AccessDeniedException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()

        log.warn { "Access denied error: ${ex.message}" }
        log.warn { "Request info: ${request.getDescription(true)}" }

        mailService.sendSystemNotification(ex.stackTraceString, id)

        val resp = RequestErrorResponse(
            id,
            ReqError.ROLE_NOT_ALLOWED.errorCodeStr,
            emptyMap(),
            "Access denied for this request due to insufficient access privileges."
        )

        return ResponseEntity(resp, HttpStatus.FORBIDDEN)
    }

    // https://www.baeldung.com/spring-boot-bean-validation and
    // https://stackoverflow.com/questions/51991992/getting-ambiguous-exceptionhandler-method-mapped-for-methodargumentnotvalidexce?rq=1
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.info { "MethodArgumentNotValidException: ${ex.message.oneLine()}" }
        log.info { "Request info: ${request.getDescription(true)}" }

        val msg = ex.bindingResult.allErrors
            .joinToString(separator = ";") { "'${(it as FieldError).field}': ${it.getDefaultMessage()};" }

        val resp = RequestErrorResponse(id, ReqError.INVALID_PARAMETER_VALUE.errorCodeStr, emptyMap(), msg)
        return ResponseEntity(resp, HttpStatus.BAD_REQUEST)
    }

    // https://stackoverflow.com/questions/44850637/how-to-handle-json-parse-error-in-spring-rest-web-service
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.info { "HttpMessageNotReadableException: ${ex.message.oneLine()}" }
        log.info { "Request info: ${request.getDescription(true)}" }

        // Its own code rather than INVALID_PARAMETER_VALUE: the client can do something specific
        // about it, and `field` says where. See RejectNulCharacters for why this is refused here.
        val nul = ex.cause as? NulCharacterException
        if (nul != null) {
            val resp = RequestErrorResponse(
                id,
                ReqError.TEXT_CONTAINS_NUL.errorCodeStr,
                mapOf("field" to nul.field),
                "'${nul.field}' contains the NUL character (U+0000), which cannot be stored."
            )
            return ResponseEntity(resp, HttpStatus.BAD_REQUEST)
        }

        val msg = when (ex.cause) {
            is InvalidNullException -> {
                val cause = ex.cause as InvalidNullException
                "Missing parameter '${cause.propertyName.simpleName}' of type '${
                    cause.targetType.toString().replace("kotlin.", "")
                }'"
            }

            is MismatchedInputException -> {
                val cause = ex.cause as MismatchedInputException
                cause.originalMessage
            }

            is JsonParseException -> "Invalid JSON format: JSON parsing failed"
            else -> "Request body is malformed."
        }

        val resp = RequestErrorResponse(id, ReqError.INVALID_PARAMETER_VALUE.errorCodeStr, emptyMap(), msg)
        return ResponseEntity(resp, HttpStatus.BAD_REQUEST)
    }
}