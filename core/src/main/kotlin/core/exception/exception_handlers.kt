package core.exception

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import core.aas.ExecutorOverloadException
import core.util.SendMailService
import mu.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.util.*


private val log = KotlinLogging.logger {}

@ControllerAdvice
class EasyExceptionHandler(private val mailService: SendMailService) : ResponseEntityExceptionHandler() {

    @ExceptionHandler(value = [ExecutorOverloadException::class])
    fun handleExecutorOverloadException(ex: ExecutorOverloadException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.warn("ExecutorOverloadException", ex)
        log.warn("Request info: ${request.getDescription(true)}")
        mailService.sendSystemNotification(ex.stackTraceString, id)
        // TODO: also use code
        return ResponseEntity(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @ExceptionHandler(value = [ForbiddenException::class])
    fun handleForbiddenException(ex: ForbiddenException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()

        log.warn("Forbidden error: ${ex.message}")
        log.warn("Request info: ${request.getDescription(true)}")

        mailService.sendSystemNotification(ex.stackTraceString, id)

        val resp = RequestErrorResponse(id, ex.code.errorCodeStr, mapOf(*ex.attributes), ex.message)
        return ResponseEntity(resp, HttpStatus.FORBIDDEN)
    }

    @ExceptionHandler(value = [InvalidRequestException::class])
    fun handleInvalidReqException(ex: InvalidRequestException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()

        log.warn("Invalid request, message: ${ex.message}, id: $id")
        log.warn("Request info: ${request.getDescription(true)}")

        if (ex.notify) {
            mailService.sendSystemNotification(ex.stackTraceString, id)
        }

        val resp = RequestErrorResponse(id, ex.code?.errorCodeStr, mapOf(*ex.attributes), ex.message)
        return ResponseEntity(resp, HttpStatus.BAD_REQUEST)
    }

    @ExceptionHandler(value = [Exception::class])
    fun handleGenericException(ex: Exception, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.error("Caught a big one", ex)
        log.error("Request info: ${request.getDescription(true)}")
        mailService.sendSystemNotification(ex.stackTraceString, id)
        return ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @ExceptionHandler(value = [AccessDeniedException::class])
    fun handleAccessDeniedException(ex: AccessDeniedException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()

        log.warn("Access denied error: ${ex.message}")
        log.warn("Request info: ${request.getDescription(true)}")

        mailService.sendSystemNotification(ex.stackTraceString, id)

        val resp = RequestErrorResponse(
            id,
            ReqError.ROLE_NOT_ALLOWED.errorCodeStr,
            emptyMap(),
            "Access denied for this request due to insufficient access privileges."
        )

        return ResponseEntity(resp, HttpStatus.FORBIDDEN)
    }

    @ExceptionHandler(value = [AwaitTimeoutException::class])
    fun handleAwaitTimeoutException(ex: AwaitTimeoutException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.info("AwaitTimeoutException: ${ex.message}")
        log.info("Request info: ${request.getDescription(true)}")
        mailService.sendSystemNotification(ex.stackTraceString, id)

        val resp = RequestErrorResponse(id, ex.code.errorCodeStr, ex.attributes.toMap(), ex.message)
        return ResponseEntity(resp, HttpStatus.INTERNAL_SERVER_ERROR)
    }


    // https://www.baeldung.com/spring-boot-bean-validation and
    // https://stackoverflow.com/questions/51991992/getting-ambiguous-exceptionhandler-method-mapped-for-methodargumentnotvalidexce?rq=1
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatus,
        request: WebRequest
    ): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.info("MethodArgumentNotValidException: ${ex.message}")
        log.info("Request info: ${request.getDescription(true)}")

        val msg = ex.bindingResult.allErrors
            .joinToString(separator = ";") { "'${(it as FieldError).field}': ${it.getDefaultMessage()};" }

        val resp = RequestErrorResponse(id, ReqError.INVALID_PARAMETER_VALUE.errorCodeStr, emptyMap(), msg)
        return ResponseEntity(resp, HttpStatus.BAD_REQUEST)
    }

    // https://stackoverflow.com/questions/44850637/how-to-handle-json-parse-error-in-spring-rest-web-service
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatus,
        request: WebRequest
    ): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.info("HttpMessageNotReadableException: ${ex.message}")
        log.info("Request info: ${request.getDescription(true)}")

        val msg = when (ex.cause) {
            is MissingKotlinParameterException -> {
                val cause = ex.cause as MissingKotlinParameterException
                "Missing parameter '${cause.parameter.name}' of type '${
                    cause.parameter.type.toString().replace("kotlin.", "")
                }'"
            }
            is MismatchedInputException -> {
                val cause = ex.cause as MismatchedInputException
                cause.originalMessage
            }
            is InvalidFormatException -> {
                val cause = ex.cause as InvalidFormatException
                "Cannot parse parameter '${cause.value}' to '${cause.targetType}'"
            }
            is JsonParseException -> "Invalid JSON format: JSON parsing failed"
            else -> ex.message ?: "Invalid request!"
        }

        val resp = RequestErrorResponse(id, ReqError.INVALID_PARAMETER_VALUE.errorCodeStr, emptyMap(), msg)
        return ResponseEntity(resp, HttpStatus.BAD_REQUEST)
    }
}