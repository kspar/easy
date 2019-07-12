package core.exception

import core.ems.service.course.StudentNotFoundException
import core.util.SendMailService
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

private val log = KotlinLogging.logger {}

@ControllerAdvice
class EmsExceptionHandler(private val mailService: SendMailService) : ResponseEntityExceptionHandler() {

    @ExceptionHandler(value = [StudentNotFoundException::class])
    fun handleStudentNotFoundException(ex: StudentNotFoundException, request: WebRequest): ResponseEntity<String> {
        log.info("StudentNotFoundException: ${ex.message}")
        log.info("Request info: ${request.getDescription(true)}")
        mailService.sendSystemNotification(ex.stackTraceString)
        return ResponseEntity(ex.message, HttpStatus.BAD_REQUEST)
    }

    @ExceptionHandler(value = [ForbiddenException::class])
    fun handleForbiddenException(ex: ForbiddenException, request: WebRequest): ResponseEntity<Any> {
        log.warn("ForbiddenException", ex)
        log.warn("Request info: ${request.getDescription(true)}")
        mailService.sendSystemNotification(ex.stackTraceString)
        return ResponseEntity(HttpStatus.FORBIDDEN)
    }

    @ExceptionHandler(value = [InvalidRequestException::class])
    fun handleInvalidReqException(ex: InvalidRequestException, request: WebRequest): ResponseEntity<Any> {
        log.warn("InvalidRequestException", ex)
        log.warn("Request info: ${request.getDescription(true)}")
        mailService.sendSystemNotification(ex.stackTraceString)
        return ResponseEntity(HttpStatus.BAD_REQUEST)
    }

    @ExceptionHandler(value = [Exception::class])
    fun handleGenericException(ex: Exception, request: WebRequest): ResponseEntity<Any> {
        log.error("Caught a big one", ex)
        log.error("Request info: ${request.getDescription(true)}")
        mailService.sendSystemNotification(ex.stackTraceString)
        return ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR)
    }
}

