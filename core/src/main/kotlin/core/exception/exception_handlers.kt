package core.exception

import core.aas.ExecutorOverloadException
import core.ems.service.course.StudentNotFoundException
import core.util.SendMailService
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.util.*

private val log = KotlinLogging.logger {}

@ControllerAdvice
class EasyExceptionHandler(private val mailService: SendMailService) : ResponseEntityExceptionHandler() {

    @ExceptionHandler(value = [StudentNotFoundException::class])
    fun handleStudentNotFoundException(ex: StudentNotFoundException, request: WebRequest): ResponseEntity<String> {
        val id = UUID.randomUUID().toString()
        log.info("StudentNotFoundException: ${ex.message}")
        log.info("Request info: ${request.getDescription(true)}")
        mailService.sendSystemNotification(ex.stackTraceString, id)
        return ResponseEntity(ex.message, HttpStatus.BAD_REQUEST)
    }

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

        // Remove after migrating all FEs to use code
        if (ex.code != null) {
            val resp = RequestErrorResponse(id, ex.code.errorCodeStr, mapOf(*ex.attributes), ex.message)
            return ResponseEntity(resp, HttpStatus.FORBIDDEN)
        } else {
            return ResponseEntity(HttpStatus.FORBIDDEN)
        }
    }

    @ExceptionHandler(value = [InvalidRequestException::class])
    fun handleInvalidReqException(ex: InvalidRequestException, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()

        log.warn("Invalid request, message: ${ex.message}, id: $id")
        log.warn("Request info: ${request.getDescription(true)}")

        mailService.sendSystemNotification(ex.stackTraceString, id)

        // Remove after migrating all IREs to use code
        if (ex.code != null) {
            val resp = RequestErrorResponse(id, ex.code.errorCodeStr, mapOf(*ex.attributes), ex.message)
            return ResponseEntity(resp, HttpStatus.BAD_REQUEST)
        } else {
            return ResponseEntity(HttpStatus.BAD_REQUEST)
        }

    }

    @ExceptionHandler(value = [Exception::class])
    fun handleGenericException(ex: Exception, request: WebRequest): ResponseEntity<Any> {
        val id = UUID.randomUUID().toString()
        log.error("Caught a big one", ex)
        log.error("Request info: ${request.getDescription(true)}")
        mailService.sendSystemNotification(ex.stackTraceString, id)
        return ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR)
    }
}
