package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.SysConf.getProp
import core.conf.security.EasyUser
import core.db.Account
import core.db.LogReport
import core.ems.service.management.ReportLogController.LogLevel
import core.ems.service.management.ReportLogController.LogLevel.*
import core.ems.service.management.ReportLogController.ReportClientSysProp.EMAIL_LOG_LEVEL
import core.ems.service.management.ReportLogController.ReportClientSysProp.NO_MAIL_CLIENT_REGEX
import core.ems.service.management.ReportLogController.Req
import core.exception.InvalidRequestException
import core.util.SendMailService
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.scheduling.annotation.Async
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReportLogController(private val mailService: SendMailService) {

    data class Req(@JsonProperty("log_level", required = true)
                   @field:NotBlank @field:Size(max = 5) val logLevel: String,

                   @JsonProperty("log_message", required = true)
                   @field:NotBlank @field:Size(max = 10000) val logMessage: String,

                   @JsonProperty("client_id", required = true)
                   @field:NotBlank @field:Size(max = 100) val clientId: String)

    enum class LogLevel(val paramValue: String) {
        DEBUG("DEBUG"),
        INFO("INFO"),
        WARN("WARN"),
        ERROR("ERROR")
    }

    enum class ReportClientSysProp(val propKey: String) {
        EMAIL_LOG_LEVEL("rcl_email_log_level"),
        NO_MAIL_CLIENT_REGEX("rcl_no_mail_client_regex")
    }

    @Async
    @PostMapping("/management/log")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser) {

        log.debug { "${caller.id} is logging $dto" }

        when (dto.logLevel) {
            DEBUG.paramValue -> {
                insertLog(dto, caller, DEBUG)
                notifyAdmin(dto, caller, DEBUG, mailService)
            }
            INFO.paramValue -> {
                insertLog(dto, caller, INFO)
                notifyAdmin(dto, caller, INFO, mailService)
            }
            WARN.paramValue -> {
                insertLog(dto, caller, WARN)
                notifyAdmin(dto, caller, WARN, mailService)
            }
            ERROR.paramValue -> {
                insertLog(dto, caller, ERROR)
                notifyAdmin(dto, caller, ERROR, mailService)
            }
            else -> throw InvalidRequestException("Invalid log level ${dto.logLevel}")
        }
    }
}

fun insertLog(dto: Req, caller: EasyUser, level: LogLevel) {
    transaction {
        LogReport.insert {
            it[userId] = EntityID(caller.id, Account)
            it[logTime] = DateTime.now()
            it[logLevel] = level.paramValue
            it[logMessage] = dto.logMessage
            it[clientId] = dto.clientId
        }
    }
}

fun notifyAdmin(dto: Req, caller: EasyUser, dtoLogLevel: LogLevel, mailService: SendMailService) {
    val emailLogLevel = getProp(EMAIL_LOG_LEVEL.propKey)
    val noLogIfMatch = getProp(NO_MAIL_CLIENT_REGEX.propKey)

    // In some cases, we want to send email based on regular expression that matches the client_id.
    if (noLogIfMatch != null && Regex(noLogIfMatch).matches(dto.clientId)) return

    // In some cases, we want to send email based on log dtoLogLevel (must be configurable). (minimal log dtoLogLevel)
    when (emailLogLevel) {
        null -> sendLogEmail(dto, caller, mailService)
        DEBUG.paramValue -> sendLogEmail(dto, caller, mailService)
        INFO.paramValue -> if (dtoLogLevel != DEBUG) sendLogEmail(dto, caller, mailService)
        WARN.paramValue -> if (dtoLogLevel == ERROR || dtoLogLevel == WARN) sendLogEmail(dto, caller, mailService)
        ERROR.paramValue -> if (dtoLogLevel == ERROR) sendLogEmail(dto, caller, mailService)
        else -> log.warn {
            ("Invalid email log level defined in the database. Expected: 'DEBUG', 'INFO', 'WARN', 'ERROR', but got ${dto.logLevel}.")
        }
    }
}

fun sendLogEmail(dto: Req, caller: EasyUser, mailService: SendMailService) {
    val id = UUID.randomUUID().toString()
    val message = """LOG TIME: $${DateTime.now()} 
                    LOG LEVEL: ${dto.logLevel}
                    CALLER ID: ${caller.id}
                    CLIENT ID: ${dto.clientId}
                    MESSAGE: ${dto.logMessage}"""

    log.debug { "Sending client log: $message" }
    mailService.sendSystemNotification(message, id)
}