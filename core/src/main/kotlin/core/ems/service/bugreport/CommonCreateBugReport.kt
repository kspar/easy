package core.ems.service.bugreport

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.SysConf
import core.conf.security.EasyUser
import core.db.Account
import core.db.BugReport
import core.db.BugReportForwardState
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.SendMailService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class CommonCreateBugReportController(
    private val mailService: SendMailService,
    private val forwardService: BugReportForwardService,
) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("message", required = true)
        @field:NotBlank @field:Size(max = 5000) val message: String,

        // Absent, not empty, when the reporter unticked the consent checkbox. The column keeps that
        // distinction; see changeset 230826-1.
        @param:JsonProperty("diagnostics")
        @field:Size(max = 20000) val diagnostics: String?,

        @param:JsonProperty("page_url")
        @field:Size(max = 2000) val pageUrl: String?,

        @param:JsonProperty("web_version")
        @field:Size(max = 100) val webVersion: String?,

        @param:JsonProperty("user_agent")
        @field:Size(max = 500) val userAgent: String?,
    )

    data class Resp(@get:JsonProperty("id") val id: String)

    // Every signed-in role, because a bug report from the person who cannot use the page is the
    // whole point and students are most of them.
    //
    // This is a write path any student can drive, and it fans out to an email and to the issue
    // tracker, so it is rate-limited below rather than left to good manners. `report_client_log.kt`
    // is the cautionary note: same shape, no limit, and its own comment says so.
    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/bug-reports")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.info { "${caller.id} is filing a bug report" }

        assertNotRateLimited(caller.id)

        // Committed before anything is attempted with it. The row is the report; the issue and the
        // email are best-effort deliveries of it, and either can fail without losing anything.
        val forwardingEnabled = forwardService.isEnabled()
        val id = transaction {
            BugReport.insertAndGetId {
                it[userId] = EntityID(caller.id, Account)
                it[createdAt] = DateTime.now()
                it[message] = dto.message
                it[diagnostics] = dto.diagnostics
                it[pageUrl] = dto.pageUrl
                it[webVersion] = dto.webVersion
                it[userAgent] = dto.userAgent
                it[ytState] =
                    if (forwardingEnabled) BugReportForwardState.PENDING else BugReportForwardState.DISABLED
                it[ytAttempts] = 0
            }
        }.value

        log.info { "Bug report $id stored for ${caller.id}, forwarding ${if (forwardingEnabled) "pending" else "disabled"}" }

        // Both @Async, so neither delays the response. The mail is the floor under the integration:
        // if YouTrack is unreachable, misconfigured, or switched off, someone still finds out.
        forwardService.forward(id)
        mailService.sendSystemNotification(notificationText(id, dto, caller))

        return Resp(id.toString())
    }

    private fun assertNotRateLimited(callerId: String) {
        val max = SysConf.getProp(MAX_PER_HOUR_PROP)?.toIntOrNull() ?: DEFAULT_MAX_PER_HOUR
        val since = DateTime.now().minusHours(1)

        val recent = transaction {
            BugReport.selectAll()
                .where { (BugReport.userId eq callerId) and (BugReport.createdAt greater since) }
                .count()
        }

        if (recent >= max) {
            // notify = false: the point of the limit is to stop a flood of admin mail, so the
            // limit itself must not send any.
            throw InvalidRequestException(
                "Too many bug reports from $callerId in the last hour: $recent (max $max)",
                ReqError.BUG_REPORT_RATE_LIMITED,
                "max_per_hour" to max.toString(),
                notify = false,
            )
        }
    }

    private fun notificationText(id: Long, dto: Req, caller: EasyUser) = """
        BUG REPORT: $id
        FROM: ${caller.id} <${caller.email}> ${caller.roles}
        PAGE: ${dto.pageUrl ?: "-"}
        WEB: ${dto.webVersion ?: "-"}

        ${dto.message}
    """.trimIndent()

    companion object {
        const val MAX_PER_HOUR_PROP = "bug_report_max_per_hour"
        const val DEFAULT_MAX_PER_HOUR = 10
    }
}
