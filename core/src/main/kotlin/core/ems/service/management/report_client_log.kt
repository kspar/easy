package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.SysConf.getProp
import core.conf.security.EasyUser
import core.db.Account
import core.db.LogReport
import core.ems.service.management.ReportLogController.ReportClientSysProp.EMAIL_LOG_LEVEL
import core.ems.service.management.ReportLogController.ReportClientSysProp.NO_MAIL_CLIENT_REGEX
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReportLogController {

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


    @PostMapping("/management/log")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser) {

        when (dto.logLevel) {
            LogLevel.DEBUG.paramValue -> insertLog(dto, caller, LogLevel.DEBUG)
            LogLevel.INFO.paramValue -> insertLog(dto, caller, LogLevel.INFO)
            LogLevel.WARN.paramValue -> insertLog(dto, caller, LogLevel.WARN)
            LogLevel.ERROR.paramValue -> insertLog(dto, caller, LogLevel.ERROR)
            else -> throw InvalidRequestException("Invalid log level ${dto.logLevel}")
        }

        // In some cases, we want to send email based on log level (must be configurable). (minimal log level)
        val emailLevel = getProp(EMAIL_LOG_LEVEL.propKey)
        // In some cases, we want to send email based on regular expression that matches the client_id.
        val noLogIfMatch = getProp(NO_MAIL_CLIENT_REGEX.propKey)
    }
}

fun insertLog(dto: ReportLogController.Req, caller: EasyUser, level: ReportLogController.LogLevel) {
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