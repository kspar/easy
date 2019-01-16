package ee.urgas.aas.exception

import ee.urgas.aas.util.SendMailService
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

    @ExceptionHandler(value = [Exception::class])
    fun handleGenericException(ex: Exception, request: WebRequest): ResponseEntity<Any> {
        log.error("Caught a big one", ex)
        log.error("Request info: ${request.getDescription(true)}")

        mailService.sendSystemNotification(ex.stackTraceString)

        return ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR)
    }
}